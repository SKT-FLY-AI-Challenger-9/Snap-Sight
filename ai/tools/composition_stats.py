"""구도 통계 추출기 (PC 전용) — 잘 찍힌 인물 사진에서 구도 기준값을 데이터로 만든다.

사진 폴더(예: ``fetch_unsplash_photos.py`` 결과)를 받아 사진마다:
  1) 앱과 같은 YOLO26n Objects365 체크포인트로 person bbox 를 잡고 (단독 피사체만)
  2) 앱 PersonFramingPoseTracker 처럼 person bbox(+25% 패딩)로 크롭한 뒤
     MediaPipe PoseLandmarker 로 코(NOSE)·발목(ANKLE 평균) y 를 얻어
  3) 전체 화면 기준 정규화 좌표로 환산한다 — 앱의 headY/footY 와 동일한 의미.

결과는 JSONL(사진별 원시 값) + 콘솔 집계표(백분위수)로 나온다. 집계표는 앱의
``PersonFramingController`` 목표 밴드(머리 0~0.15, 발 0.60~0.80)와 나란히 보여줘
실데이터 기반으로 임계값을 검증·튜닝할 수 있게 한다.

사용:
    python -m ai.tools.composition_stats <사진 폴더> [--jsonl <출력.jsonl>]

첫 실행 시 YOLO 체크포인트(ultralytics)와 PoseLandmarker 모델(~6MB)을 내려받는다.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

import cv2
import numpy as np

from ai.on_device_cv.detectors import UltralyticsDetectorConfig, UltralyticsYoloDetector
from ai.tools.verify_portrait_crop import IMAGE_SUFFIXES

# 앱 PersonFramingController 의 현재 목표 밴드 — 집계표 비교 기준
APP_CASE2_HEAD = (0.00, 0.15)
APP_CASE2_FOOT = (0.60, 0.80)
APP_CLOSE_ENOUGH_HEIGHT = 0.80

PERSON_CONFIDENCE_T = 0.25
PERSON_DOMINANT_RATIO = 2.0  # 최대 bbox 가 2위의 이 배 이상일 때만 단독 피사체
PADDING_RATIO = 0.25  # 앱 PersonFramingPoseTracker 와 동일
MIN_VISIBILITY = 0.3  # 앱 MIN_LIKELIHOOD 와 동일한 역할

# MediaPipe PoseLandmarker 인덱스 — ML Kit PoseLandmark 와 같은 BlazePose 33점 체계
NOSE = 0
LEFT_ANKLE = 27
RIGHT_ANKLE = 28

_POSE_MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
    "pose_landmarker_lite/float16/latest/pose_landmarker_lite.task"
)
_POSE_MODEL_PATH = Path(__file__).with_name("pose_landmarker_lite.task")


def _ensure_pose_model() -> Path:
    if not _POSE_MODEL_PATH.exists():
        print(f"PoseLandmarker 모델을 내려받는 중... ({_POSE_MODEL_URL})")
        urllib.request.urlretrieve(_POSE_MODEL_URL, _POSE_MODEL_PATH)
    return _POSE_MODEL_PATH


def _create_landmarker():
    import mediapipe as mp
    from mediapipe.tasks.python import BaseOptions
    from mediapipe.tasks.python import vision

    options = vision.PoseLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=str(_ensure_pose_model())),
    )
    return mp, vision.PoseLandmarker.create_from_options(options)


def dominant_person(detections) -> object | None:
    persons = sorted(
        (d for d in detections if d.label == "person" and d.confidence >= PERSON_CONFIDENCE_T),
        key=lambda d: (d.bbox.x_max - d.bbox.x_min) * (d.bbox.y_max - d.bbox.y_min),
        reverse=True,
    )
    if not persons:
        return None
    if len(persons) >= 2:
        first = (persons[0].bbox.x_max - persons[0].bbox.x_min) * (
            persons[0].bbox.y_max - persons[0].bbox.y_min
        )
        second = (persons[1].bbox.x_max - persons[1].bbox.x_min) * (
            persons[1].bbox.y_max - persons[1].bbox.y_min
        )
        if first < PERSON_DOMINANT_RATIO * second:
            return None
    return persons[0]


def padded_region(bbox, width: int, height: int) -> tuple[int, int, int, int]:
    """앱 PersonFramingPoseTracker.paddedRegion 과 동일 규칙 (픽셀 좌표)."""
    x_min = bbox.x_min * width
    y_min = bbox.y_min * height
    x_max = bbox.x_max * width
    y_max = bbox.y_max * height
    pad_x = (x_max - x_min) * PADDING_RATIO
    pad_y = (y_max - y_min) * PADDING_RATIO
    left = int(max(0.0, x_min - pad_x))
    top = int(max(0.0, y_min - pad_y))
    right = int(min(float(width), x_max + pad_x))
    bottom = int(min(float(height), y_max + pad_y))
    return left, top, right, bottom


def landmark_y(landmarks, index: int, region_top: int, region_height: int, frame_height: int) -> float | None:
    lm = landmarks[index]
    visibility = getattr(lm, "visibility", None)
    if visibility is not None and visibility < MIN_VISIBILITY:
        return None
    y = (region_top + lm.y * region_height) / frame_height
    return min(1.0, max(0.0, y))


def process(path: Path, detector, mp_module, landmarker) -> dict | None:
    image = cv2.imread(str(path))
    if image is None:
        print(f"{path.name}: 읽기 실패", file=sys.stderr)
        return None
    height, width = image.shape[:2]

    person = dominant_person(detector.detect(image))
    if person is None:
        print(f"{path.name}: 단독 person 없음 — 건너뜀")
        return None

    left, top, right, bottom = padded_region(person.bbox, width, height)
    crop = image[top:bottom, left:right]
    if crop.size == 0:
        return None
    rgb = cv2.cvtColor(np.ascontiguousarray(crop), cv2.COLOR_BGR2RGB)
    result = landmarker.detect(mp_module.Image(image_format=mp_module.ImageFormat.SRGB, data=rgb))
    if not result.pose_landmarks:
        print(f"{path.name}: 포즈 검출 실패 — 건너뜀")
        return None
    landmarks = result.pose_landmarks[0]

    region_h = bottom - top
    head_y = landmark_y(landmarks, NOSE, top, region_h, height)
    ankle_l = landmark_y(landmarks, LEFT_ANKLE, top, region_h, height)
    ankle_r = landmark_y(landmarks, RIGHT_ANKLE, top, region_h, height)
    ankles = [y for y in (ankle_l, ankle_r) if y is not None]
    foot_y = sum(ankles) / len(ankles) if ankles else None

    bbox = person.bbox
    return {
        "photo": path.name,
        "head_y": head_y,
        "foot_y": foot_y,
        "bbox_top": round(bbox.y_min, 4),
        "bbox_bottom": round(bbox.y_max, 4),
        "bbox_height_ratio": round(bbox.y_max - bbox.y_min, 4),
        "bbox_center_x": round((bbox.x_min + bbox.x_max) / 2.0, 4),
        "confidence": round(person.confidence, 3),
    }


def percentile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * q
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def summarize(rows: list[dict]) -> None:
    metrics = [
        ("머리(코) y", "head_y", APP_CASE2_HEAD),
        ("발(발목 평균) y", "foot_y", APP_CASE2_FOOT),
        ("bbox 상단 y", "bbox_top", None),
        ("bbox 하단 y", "bbox_bottom", None),
        ("bbox 높이 비율", "bbox_height_ratio", None),
        ("bbox 중심 x", "bbox_center_x", None),
    ]
    print(f"\n분석 {len(rows)}장 — 값은 화면 전체 기준 정규화(0=위/왼쪽, 1=아래/오른쪽)\n")
    print("| 지표 | n | p10 | p25 | p50 | p75 | p90 | 앱 목표 밴드 | 밴드 적중률 |")
    print("|---|---|---|---|---|---|---|---|---|")
    for name, key, band in metrics:
        values = [row[key] for row in rows if row.get(key) is not None]
        if not values:
            print(f"| {name} | 0 | - | - | - | - | - | - | - |")
            continue
        cells = [f"{percentile(values, q):.3f}" for q in (0.10, 0.25, 0.50, 0.75, 0.90)]
        if band:
            hit = sum(1 for v in values if band[0] <= v <= band[1]) / len(values)
            band_text = f"{band[0]:.2f}~{band[1]:.2f}"
            hit_text = f"{hit:.0%}"
        else:
            band_text = hit_text = "-"
        print(f"| {name} | {len(values)} | " + " | ".join(cells) + f" | {band_text} | {hit_text} |")

    heights = [row["bbox_height_ratio"] for row in rows]
    close = sum(1 for v in heights if v >= APP_CLOSE_ENOUGH_HEIGHT) / len(heights)
    print(
        f"\n참고: bbox 높이 ≥ {APP_CLOSE_ENOUGH_HEIGHT:.0%} (앱 CLOSE_ENOUGH 기준) 인 사진 비율: {close:.0%}"
    )
    print("적중률이 낮은 지표는 앱 PersonFramingController 의 목표 밴드 재조정 후보다.")


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(prog="python -m ai.tools.composition_stats")
    parser.add_argument("input_dir", type=Path, help="사진 폴더")
    parser.add_argument("--jsonl", type=Path, default=None, help="사진별 원시 값 출력 (기본 <폴더>_stats.jsonl)")
    args = parser.parse_args()

    input_dir: Path = args.input_dir
    if not input_dir.is_dir():
        print(f"입력 폴더가 없습니다: {input_dir}", file=sys.stderr)
        return 1
    photos = sorted(p for p in input_dir.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES)
    if not photos:
        print(f"입력 폴더에 사진이 없습니다: {input_dir}", file=sys.stderr)
        return 1

    detector = UltralyticsYoloDetector(
        UltralyticsDetectorConfig(confidence_threshold=PERSON_CONFIDENCE_T)
    )
    detector.load()
    mp_module, landmarker = _create_landmarker()

    rows: list[dict] = []
    try:
        for path in photos:
            row = process(path, detector, mp_module, landmarker)
            if row is not None:
                rows.append(row)
    finally:
        landmarker.close()
        detector.close()

    if not rows:
        print("분석 가능한 사진이 없습니다 (단독 인물 + 포즈 검출 성공 기준).", file=sys.stderr)
        return 1

    jsonl_path: Path = args.jsonl or input_dir.parent / f"{input_dir.name}_stats.jsonl"
    with jsonl_path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"\n원시 값: {jsonl_path}")

    summarize(rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
