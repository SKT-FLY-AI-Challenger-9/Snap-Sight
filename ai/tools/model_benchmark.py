"""검출 모델 벤치마크 (PC 전용) — 후보 체크포인트들의 지연·인물 검출력 비교.

구도 데이터셋(또는 임의 사진 폴더)에 여러 YOLO 체크포인트를 돌려 한 표로 비교한다.
같은 Objects365 taxonomy 모델(yolo26n/s/m-objv1)은 앱에 그대로 교체 가능한 후보이고,
COCO 모델(yolo26n/s·yolo11n·yolov8n)은 person 검출력·속도의 비교 기준선으로만 쓴다
(클래스 계약이 달라 앱에는 못 꽂는다 — `ai/on_device_cv/README.md` taxonomy 절 참고).

사용:
    python -m ai.tools.model_benchmark <사진 폴더> [--limit 80] [--models a.pt b.pt ...]

지표 (사진별 1회 추론, CPU·imgsz 640):
  - 지연 p50/p95 (ms) — 워밍업 2장 제외
  - 인물 검출률: person 을 1개 이상 찾은 사진 비율 (conf ≥ 0.25)
  - 단독 인물률: 우리 파이프라인의 지배 피사체 규칙(최대 bbox 가 2위의 2배 이상)을
    통과한 사진 비율 — 구도 모드가 실제로 쓸 수 있는 프레임의 비율
  - person 평균 confidence
"""

from __future__ import annotations

import argparse
import contextlib
import io
import sys
import time
from pathlib import Path

import cv2

from ai.tools.verify_portrait_crop import IMAGE_SUFFIXES

DEFAULT_MODELS = [
    "yolo26n-objv1-150.pt",  # 현재 앱 모델
    "yolo26s-objv1-150.pt",
    "yolo26m-objv1-150.pt",
    "yolo26n.pt",  # COCO 기준선
    "yolo26s.pt",
    "yolo11n.pt",
    "yolov8n.pt",
    # 비-YOLO 기준선 (전부 COCO 계열 — 앱 교체 후보 아님)
    "rtdetr-l.pt",  # 트랜스포머 (RT-DETR)
    "tv:ssdlite320_mobilenet_v3",  # 1-stage 모바일급
    "tv:fasterrcnn_mobilenet_v3",  # 2-stage 모바일급
    "mp:efficientdet_lite0",  # MediaPipe 온디바이스급
]
CONF_T = 0.25
DOMINANT_RATIO = 2.0
WARMUP = 2


def percentile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return float("nan")
    position = (len(ordered) - 1) * q
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def person_class_ids(names) -> set[int]:
    items = names.items() if isinstance(names, dict) else enumerate(names)
    return {int(i) for i, n in items if str(n).strip().lower() == "person"}


# ---- 백엔드 어댑터 — 이름으로 골라 (사람 후보 [(bbox넓이, conf)], 클래스 수) 를 돌려준다 ----


def _ultralytics_adapter(model_name: str):
    from ultralytics import RTDETR, YOLO

    buf = io.StringIO()
    with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
        model = (RTDETR if model_name.startswith("rtdetr") else YOLO)(model_name)
    person_ids = person_class_ids(model.names)

    def detect(frame):
        result = model.predict(source=frame, imgsz=640, conf=CONF_T, device="cpu", verbose=False)[0]
        boxes = result.boxes
        if boxes is None or len(boxes) == 0:
            return []
        return [
            (float(a[2] - a[0]) * float(a[3] - a[1]), float(c))
            for a, c, k in zip(boxes.xyxy, boxes.conf, boxes.cls)
            if int(k) in person_ids
        ]

    return detect, len(model.names)


def _torchvision_adapter(model_name: str):
    """COCO91 체계 — person 은 label 1. 모델은 자체 전처리로 resize 한다."""
    import torch
    from torchvision.models import detection as tvd

    factories = {
        "tv:ssdlite320_mobilenet_v3": (tvd.ssdlite320_mobilenet_v3_large, "DEFAULT"),
        "tv:fasterrcnn_mobilenet_v3": (tvd.fasterrcnn_mobilenet_v3_large_fpn, "DEFAULT"),
        "tv:retinanet_resnet50": (tvd.retinanet_resnet50_fpn_v2, "DEFAULT"),
    }
    factory, weights = factories[model_name]
    model = factory(weights=weights)
    model.eval()

    @torch.inference_mode()
    def detect(frame):
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        tensor = torch.from_numpy(rgb).permute(2, 0, 1).float() / 255.0
        out = model([tensor])[0]
        persons = []
        for box, label, score in zip(out["boxes"], out["labels"], out["scores"]):
            if int(label) == 1 and float(score) >= CONF_T:
                x1, y1, x2, y2 = (float(v) for v in box)
                persons.append(((x2 - x1) * (y2 - y1), float(score)))
        return persons

    return detect, 91


_MEDIAPIPE_MODELS = {
    "mp:efficientdet_lite0": "efficientdet_lite0",
    "mp:efficientdet_lite2": "efficientdet_lite2",
}


def _mediapipe_adapter(model_name: str):
    import urllib.request

    import mediapipe as mp
    from mediapipe.tasks.python import BaseOptions, vision

    slug = _MEDIAPIPE_MODELS[model_name]
    path = Path(__file__).with_name(f"{slug}.tflite")
    if not path.exists():
        urllib.request.urlretrieve(
            "https://storage.googleapis.com/mediapipe-models/object_detector/"
            f"{slug}/float32/latest/{slug}.tflite",
            path,
        )
    detector = vision.ObjectDetector.create_from_options(
        vision.ObjectDetectorOptions(
            base_options=BaseOptions(model_asset_path=str(path)),
            score_threshold=CONF_T,
            max_results=50,
        )
    )

    def detect(frame):
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        result = detector.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
        persons = []
        for detection in result.detections:
            category = detection.categories[0]
            if category.category_name == "person":
                b = detection.bounding_box
                persons.append((float(b.width * b.height), float(category.score)))
        return persons

    return detect, 80  # COCO 학습(라벨맵 89종 노출)


_VLM_PROMPT = (
    "이미지에서 사람(person)마다 바운딩 박스를 찾아 JSON 배열로만 답하세요. "
    '형식: [{"x1":0.1,"y1":0.2,"x2":0.5,"y2":0.9,"conf":0.95}] — 좌표는 0~1 정규화, '
    "conf 는 확신도. 사람이 없으면 []. JSON 외 다른 텍스트 금지."
)


def _encode_for_vlm(frame) -> bytes:
    """토큰 절약을 위해 최장변 768px JPEG 로 줄인다 (검출 대상이 사람이라 충분)."""
    height, width = frame.shape[:2]
    scale = 768 / max(height, width)
    if scale < 1.0:
        frame = cv2.resize(frame, (int(width * scale), int(height * scale)))
    ok, buffer = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 85])
    if not ok:
        raise RuntimeError("JPEG 인코딩 실패")
    return buffer.tobytes()


def _parse_vlm_persons(text: str) -> list[tuple[float, float]]:
    import json
    import re

    match = re.search(r"\[.*\]", text, re.DOTALL)
    if not match:
        return []
    try:
        boxes = json.loads(match.group(0))
    except json.JSONDecodeError:
        return []
    persons = []
    for box in boxes:
        try:
            area = max(0.0, float(box["x2"]) - float(box["x1"])) * max(
                0.0, float(box["y2"]) - float(box["y1"])
            )
            persons.append((area, float(box.get("conf", 0.5))))
        except (KeyError, TypeError, ValueError):
            continue
    return persons


def _claude_adapter(model_name: str):
    """VLM 기준선 — Anthropic 비전. 지연은 API 왕복이라 로컬 모델과 직접 비교 불가(표에 명시)."""
    import base64

    from anthropic import Anthropic
    from dotenv import load_dotenv

    load_dotenv()
    client = Anthropic()
    model_id = model_name.split(":", 1)[1]

    def detect(frame):
        payload = base64.standard_b64encode(_encode_for_vlm(frame)).decode()
        response = client.messages.create(
            model=model_id,
            max_tokens=512,
            messages=[{
                "role": "user",
                "content": [
                    {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": payload}},
                    {"type": "text", "text": _VLM_PROMPT},
                ],
            }],
        )
        return _parse_vlm_persons("".join(b.text for b in response.content if b.type == "text"))

    return detect, 0  # 개방 어휘 — 클래스 수 개념 없음


def _together_adapter(model_name: str):
    """Qwen 등 Together 호스팅 VLM — TOGETHER_API_KEY 필요."""
    import base64
    import os

    import requests as rq
    from dotenv import load_dotenv

    load_dotenv()
    model_id = model_name.split(":", 1)[1]
    key = os.environ["TOGETHER_API_KEY"]

    def detect(frame):
        payload = base64.standard_b64encode(_encode_for_vlm(frame)).decode()
        response = rq.post(
            "https://api.together.xyz/v1/chat/completions",
            headers={"Authorization": f"Bearer {key}"},
            json={
                "model": model_id,
                "max_tokens": 512,
                "messages": [{
                    "role": "user",
                    "content": [
                        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{payload}"}},
                        {"type": "text", "text": _VLM_PROMPT},
                    ],
                }],
            },
            timeout=60,
        )
        response.raise_for_status()
        return _parse_vlm_persons(response.json()["choices"][0]["message"]["content"])

    return detect, 0


def _gemini_adapter(model_name: str):
    """VLM 기준선 — Google Gemini. GEMINI_API_KEY 필요."""
    import base64
    import os

    import requests as rq
    from dotenv import load_dotenv

    load_dotenv()
    model_id = model_name.split(":", 1)[1]
    key = os.environ["GEMINI_API_KEY"]

    def detect(frame):
        payload = base64.standard_b64encode(_encode_for_vlm(frame)).decode()
        response = rq.post(
            f"https://generativelanguage.googleapis.com/v1beta/models/{model_id}:generateContent",
            headers={"x-goog-api-key": key},
            json={
                "contents": [{
                    "parts": [
                        {"inline_data": {"mime_type": "image/jpeg", "data": payload}},
                        {"text": _VLM_PROMPT},
                    ],
                }],
                "generationConfig": {"maxOutputTokens": 512},
            },
            timeout=60,
        )
        response.raise_for_status()
        parts = response.json()["candidates"][0]["content"]["parts"]
        return _parse_vlm_persons("".join(p.get("text", "") for p in parts))

    return detect, 0


def _openai_adapter(model_name: str):
    """VLM 기준선 — OpenAI (ChatGPT 계열). OPENAI_API_KEY 필요."""
    import base64
    import os

    import requests as rq
    from dotenv import load_dotenv

    load_dotenv()
    model_id = model_name.split(":", 1)[1]
    key = os.environ["OPENAI_API_KEY"]

    def detect(frame):
        payload = base64.standard_b64encode(_encode_for_vlm(frame)).decode()
        response = rq.post(
            "https://api.openai.com/v1/chat/completions",
            headers={"Authorization": f"Bearer {key}"},
            json={
                "model": model_id,
                "max_tokens": 512,
                "messages": [{
                    "role": "user",
                    "content": [
                        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{payload}"}},
                        {"type": "text", "text": _VLM_PROMPT},
                    ],
                }],
            },
            timeout=60,
        )
        response.raise_for_status()
        return _parse_vlm_persons(response.json()["choices"][0]["message"]["content"])

    return detect, 0


def make_adapter(model_name: str):
    if model_name.startswith("tv:"):
        return _torchvision_adapter(model_name)
    if model_name.startswith("mp:"):
        return _mediapipe_adapter(model_name)
    if model_name.startswith("claude:"):
        return _claude_adapter(model_name)
    if model_name.startswith("gemini:"):
        return _gemini_adapter(model_name)
    if model_name.startswith("openai:"):
        return _openai_adapter(model_name)
    if model_name.startswith("together:"):
        return _together_adapter(model_name)
    return _ultralytics_adapter(model_name)


def bench_model(model_name: str, images: list) -> dict:
    detect, num_classes = make_adapter(model_name)

    latencies: list[float] = []
    person_found = 0
    dominant_ok = 0
    confidences: list[float] = []
    for index, frame in enumerate(images):
        started = time.perf_counter()
        persons = detect(frame)
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        if index >= WARMUP:
            latencies.append(elapsed_ms)
        if not persons:
            continue
        person_found += 1
        persons.sort(reverse=True)
        confidences.append(persons[0][1])
        if len(persons) == 1 or persons[0][0] >= DOMINANT_RATIO * persons[1][0]:
            dominant_ok += 1

    n = len(images)
    return {
        "model": model_name,
        "p50_ms": percentile(latencies, 0.5),
        "p95_ms": percentile(latencies, 0.95),
        "person_rate": person_found / n,
        "dominant_rate": dominant_ok / n,
        "mean_conf": (sum(confidences) / len(confidences)) if confidences else float("nan"),
        "classes": num_classes,
    }


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(prog="python -m ai.tools.model_benchmark")
    parser.add_argument("input_dir", type=Path, help="사진 폴더")
    parser.add_argument("--limit", type=int, default=80, help="사용할 사진 수 상한 (기본 80)")
    parser.add_argument("--models", nargs="*", default=DEFAULT_MODELS)
    args = parser.parse_args()

    photos = sorted(
        p for p in args.input_dir.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES
    )[: args.limit]
    if not photos:
        print(f"사진이 없습니다: {args.input_dir}", file=sys.stderr)
        return 1
    images = [cv2.imread(str(p)) for p in photos]
    images = [im for im in images if im is not None]
    print(f"사진 {len(images)}장 × 모델 {len(args.models)}개 — CPU, imgsz 640, conf {CONF_T}\n")

    rows = []
    for name in args.models:
        try:
            row = bench_model(name, images)
        except Exception as exc:  # 모델 하나가 죽어도 표는 나온다
            print(f"{name}: 실패 — {str(exc)[:100]}", file=sys.stderr)
            continue
        rows.append(row)
        print(
            f"{row['model']:<24} p50 {row['p50_ms']:6.0f}ms  p95 {row['p95_ms']:6.0f}ms  "
            f"인물검출 {row['person_rate']:5.0%}  단독인물 {row['dominant_rate']:5.0%}  "
            f"conf {row['mean_conf']:.2f}"
        )

    print("\n| 모델 | 클래스 | p50(ms) | p95(ms) | 인물 검출률 | 단독 인물률 | 평균 conf |")
    print("|---|---|---|---|---|---|---|")
    for r in rows:
        print(
            f"| {r['model']} | {r['classes']} | {r['p50_ms']:.0f} | {r['p95_ms']:.0f} | "
            f"{r['person_rate']:.0%} | {r['dominant_rate']:.0%} | {r['mean_conf']:.2f} |"
        )
    print(
        "\n주: COCO(80클래스) 모델은 클래스 계약이 달라 앱 교체 후보가 아니라 기준선이다. "
        "단독 인물률은 구도 모드의 지배 피사체 규칙 통과 비율."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
