"""인물 3분할 자동 크롭의 PC 검증 도구 (2026-08-25).

frontend `camera/PortraitAutoCrop.kt` 의 크롭 기하를 그대로 포팅해, 가져온 사진을
"자동촬영 승자 컷"이라 가정하고 앱과 같은 판정을 돌린 뒤 전/후 비교 이미지를 만든다.
크롭 상수·수식을 한쪽에서 고치면 반드시 다른 쪽도 고칠 것.

앱과 다른 점 (검증 시 감안):
 - 얼굴 검출기가 MLKit(안드로이드 전용)이 아니라 OpenCV YuNet 이다. 기하 판정은
   동일하지만 얼굴 박스 크기·검출 성공률이 조금 다를 수 있다. 모델 파일
   (face_detection_yunet_2023mar.onnx, 232KB)은 이 파일 옆에 있어야 하며, 없으면
   스크립트가 opencv_zoo 에서 자동으로 내려받는다.

사용:
    python -m ai.tools.verify_portrait_crop <입력 폴더> [--out <결과 폴더>]

결과 폴더에는 사진마다 비교 이미지 한 장이 생긴다:
 - 크롭됨:   <이름>__CROPPED.jpg   (1열=원본, 2열=얼굴 박스(초록)+크롭 영역(노랑), 3열=크롭 결과)
 - 건너뜀:   <이름>__SKIP_<사유>.jpg (1열=원본, 2열=검출된 얼굴 박스 표시)
   사유: no_face / faces_N (2명 이상) / not_enough_margin / already_thirds /
        face_too_dominant / degenerate
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

# ---- PortraitAutoCrop.kt 과 반드시 같아야 하는 상수 ----
DETECT_MAX_SIDE = 1280
MIN_SCALE = 0.6
NOOP_SCALE = 0.97
MAX_FACE_FRACTION = 0.7

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
PREVIEW_HEIGHT = 800  # 비교 이미지의 세로 크기


@dataclass
class Box:
    left: int
    top: int
    width: int
    height: int


def compute_crop(face: Box, image_width: int, image_height: int) -> tuple[Box | None, str | None]:
    """`PortraitAutoCrop.computeCrop` 의 충실한 포팅 — (크롭 박스, 건너뜀 사유)를 반환한다."""
    if image_width < 3 or image_height < 3:
        return None, "degenerate"
    face_cx = face.left + face.width / 2.0
    face_cy = face.top + face.height / 2.0
    third = 1.0 / 3.0 if face_cx <= image_width / 2.0 else 2.0 / 3.0

    s = 1.0
    s = min(s, face_cx / (third * image_width))
    s = min(s, (image_width - face_cx) / ((1.0 - third) * image_width))
    s = min(s, 3.0 * face_cy / image_height)
    s = min(s, 3.0 * (image_height - face_cy) / (2.0 * image_height))
    if s < MIN_SCALE:
        return None, "not_enough_margin"
    if s >= NOOP_SCALE:
        return None, "already_thirds"

    crop_w = max(int(image_width * s), 1)
    crop_h = max(int(image_height * s), 1)
    if face.width > crop_w * MAX_FACE_FRACTION or face.height > crop_h * MAX_FACE_FRACTION:
        return None, "face_too_dominant"
    left = min(max(int(face_cx - third * crop_w), 0), image_width - crop_w)
    top = min(max(int(face_cy - crop_h / 3.0), 0), image_height - crop_h)
    return Box(left, top, crop_w, crop_h), None


_MODEL_URL = (
    "https://github.com/opencv/opencv_zoo/raw/main/models/"
    "face_detection_yunet/face_detection_yunet_2023mar.onnx"
)
_MODEL_PATH = Path(__file__).with_name("face_detection_yunet_2023mar.onnx")


def _ensure_model() -> Path:
    if not _MODEL_PATH.exists():
        print(f"YuNet 모델을 내려받는 중... ({_MODEL_URL})")
        import urllib.request

        urllib.request.urlretrieve(_MODEL_URL, _MODEL_PATH)
    return _MODEL_PATH


def detect_faces(image: np.ndarray) -> list[Box]:
    """앱처럼 축소본(긴 변 1280 이하)에서 검출하고 좌표를 원본 크기로 되돌린다."""
    height, width = image.shape[:2]
    scale = min(1.0, DETECT_MAX_SIDE / max(width, height))
    small = (
        cv2.resize(image, (int(width * scale), int(height * scale)))
        if scale < 1.0
        else image
    )
    detector = cv2.FaceDetectorYN_create(
        str(_ensure_model()), "", (small.shape[1], small.shape[0]), 0.7
    )
    _, detected = detector.detect(small)
    if detected is None:
        return []
    inv = 1.0 / scale
    return [
        Box(int(row[0] * inv), int(row[1] * inv), int(row[2] * inv), int(row[3] * inv))
        for row in detected
    ]


def annotate(image: np.ndarray, faces: list[Box], crop: Box | None) -> np.ndarray:
    out = image.copy()
    for face in faces:
        cv2.rectangle(
            out,
            (face.left, face.top),
            (face.left + face.width, face.top + face.height),
            (0, 255, 0),
            max(2, image.shape[0] // 400),
        )
    if crop is not None:
        cv2.rectangle(
            out,
            (crop.left, crop.top),
            (crop.left + crop.width, crop.top + crop.height),
            (0, 220, 255),
            max(3, image.shape[0] // 250),
        )
    return out


def to_preview(image: np.ndarray) -> np.ndarray:
    height, width = image.shape[:2]
    scale = PREVIEW_HEIGHT / height
    return cv2.resize(image, (max(1, int(width * scale)), PREVIEW_HEIGHT))


def _panes(*images: np.ndarray) -> np.ndarray:
    divider = np.full((PREVIEW_HEIGHT, 8, 3), 255, dtype=np.uint8)
    parts: list[np.ndarray] = []
    for image in images:
        if parts:
            parts.append(divider)
        parts.append(to_preview(image))
    return np.hstack(parts)


def process(path: Path, out_dir: Path) -> str:
    """사진 1장을 앱 파이프라인 그대로 판정하고 비교 이미지를 저장한다. 결과 태그를 반환."""
    # cv2.imread 는 EXIF 회전을 기본 적용한다 — 앱이 회전을 픽셀에 굽는 것과 동일 조건
    image = cv2.imread(str(path))
    if image is None:
        return "unreadable"
    height, width = image.shape[:2]

    faces = detect_faces(image)
    if len(faces) != 1:
        reason = "no_face" if not faces else f"faces_{len(faces)}"
        compare = _panes(image, annotate(image, faces, None))
        cv2.imwrite(str(out_dir / f"{path.stem}__SKIP_{reason}.jpg"), compare)
        return reason

    crop, reason = compute_crop(faces[0], width, height)
    if crop is None:
        compare = _panes(image, annotate(image, faces, None))
        cv2.imwrite(str(out_dir / f"{path.stem}__SKIP_{reason}.jpg"), compare)
        return reason or "skip"

    cropped = image[crop.top : crop.top + crop.height, crop.left : crop.left + crop.width]
    compare = _panes(image, annotate(image, faces, crop), cropped)
    cv2.imwrite(str(out_dir / f"{path.stem}__CROPPED.jpg"), compare)
    return "cropped"


def main() -> int:
    parser = argparse.ArgumentParser(description="인물 3분할 크롭 PC 검증")
    parser.add_argument("input_dir", type=Path, help="검증할 사진들이 든 폴더")
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="비교 결과 폴더 (기본: <입력 폴더>_result)",
    )
    args = parser.parse_args()

    input_dir: Path = args.input_dir
    if not input_dir.is_dir():
        print(f"입력 폴더가 없습니다: {input_dir}")
        return 1
    out_dir: Path = args.out or input_dir.parent / f"{input_dir.name}_result"
    out_dir.mkdir(parents=True, exist_ok=True)

    photos = sorted(
        p for p in input_dir.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES
    )
    if not photos:
        print(f"입력 폴더에 사진이 없습니다: {input_dir}")
        return 1

    counts: dict[str, int] = {}
    for path in photos:
        tag = process(path, out_dir)
        counts[tag] = counts.get(tag, 0) + 1
        print(f"{path.name}: {tag}")

    total = len(photos)
    print(f"\n총 {total}장 — " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    print(f"비교 결과: {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
