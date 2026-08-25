"""구도 크롭 실험실 (PC 전용, 2026-08-25) — 앱 포팅 전 규칙 검증용.

`verify_portrait_crop.py`(앱 미러)의 얼굴-3분할 크롭 위에, 공식화된 구도 규칙을
추가로 얹어 실험한다. 이 파일은 앱과의 미러 계약이 **없다** — 여기서 검증된 규칙만
나중에 앱으로 포팅한다.

추가된 규칙:
 1) 전신 템플릿 — 발밑 여백 3% + 눈높이 상단 1/3 (발 위치는 얼굴 크기 기반 인체 비례
    추정: 성인 서 있는 자세 근사. 추정 발 위치를 빨간 선으로 그려주므로 눈으로 검증).
 2) 샷 사다리 — 전신이 안 되면 상반신 크롭으로 강등하되, 관절 구간(골반·무릎·발목)
    에서 자르는 크롭은 배율을 살짝 줄여 회피한다 (최선 노력, 실패 시 경고 태그).
 3) 중앙 vs 3분할 판정 —
    - 배경 엣지 대칭성이 높으면 중앙 배치 (복도·문·터널류)
    - 정면 응시(요 각도 근사 |yaw| < 임계)면 중앙 유지
    - 옆을 보면 시선이 향하는 쪽에 여백을 두는 3분할 (lead room 규칙 —
      "가까운 3분할선"이 아니라 시선 기준으로 방향을 고른다)
 4) 얼굴 없는 인물(뒷모습·측면) 폴백 — YOLOX person 검출로 몸 bbox 를 얻어 같은 규칙을
    적용한다. 발끝이 bbox 하단이라 얼굴 비례 추정보다 오히려 정확하다. 시선이 없으므로
    가로 배치는 대칭→중앙, 아니면 가까운 3분할선. 앱에서는 Objects365 person bbox 가
    이미 있어 이 폴백의 포팅이 더 쉽다.

사용:
    python -m ai.tools.crop_lab <입력 폴더> [--out <결과 폴더>]

결과: 사진마다 3열 비교 이미지 (1열=원본, 2열=판정 표시, 3열=크롭 결과).
파일명에 판정이 붙는다: __CROPPED_<full|upper>_<center|third-left|third-right>[_joint-warn].jpg
2열 표시: 얼굴(초록), 크롭(노랑), 눈높이 선(파랑), 추정 발 선(빨강), 판정 점수 텍스트.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

from ai.tools.verify_portrait_crop import (
    DETECT_MAX_SIDE,
    IMAGE_SUFFIXES,
    PREVIEW_HEIGHT,
    _ensure_model,
)

# ---- 실험 파라미터 (결과 이미지의 점수 표기를 보고 튜닝한다) ----

# 인체 비례 근사 (서 있는 성인): YuNet 얼굴 박스 높이(faceH) 기준.
CROWN_ABOVE_FACE = 0.30   # 정수리 = 얼굴 박스 위 - 0.30*faceH
FEET_PER_FACE_H = 8.5     # 발끝 = 얼굴 박스 위 + 8.5*faceH (7.5등신 근사)

# 전신 템플릿: 발밑 여백 3%, 눈높이는 상단 1/3.
FEET_GAP_FRAC = 0.03
EYES_FRAC = 1.0 / 3.0
FULL_MIN_SCALE = 0.45     # 이보다 작아지면 발 추정이 이상한 것 — 상반신 경로로

# 상반신(기존 얼굴-3분할) 크롭 파라미터 — verify_portrait_crop 과 동일 값.
UPPER_MIN_SCALE = 0.6
UPPER_NOOP_SCALE = 0.97
MAX_FACE_FRACTION = 0.7

# 관절 회피 구간 — 정수리~발끝(bodyH) 대비 비율. (골반, 무릎, 발목·발)
JOINT_ZONES = [(0.47, 0.58), (0.68, 0.78), (0.90, 1.0)]

# 중앙 vs 3분할 판정.
YAW_FRONTAL_T = 0.10      # |코 편차/눈 간격| 이 이보다 작으면 정면 응시
SYM_T = 0.55              # 배경 엣지 대칭 상관이 이 이상이면 대칭 장면
EDGE_DENSITY_T = 0.04     # 엣지가 이만큼은 있어야 대칭 판정을 신뢰

# 얼굴 없는 인물 폴백 (YOLOX person 검출).
PERSON_SCORE_T = 0.4      # person 신뢰도 하한
PERSON_DOMINANT_RATIO = 2.0  # 여러 명이면 최대 박스가 2위의 이 배 이상일 때만 단독 취급
EYES_FRAC_OF_BODY = 0.11  # 눈높이 ≈ 정수리 + 몸높이의 11% (7.5등신 근사)


@dataclass
class Face:
    left: int
    top: int
    width: int
    height: int
    eye_l: tuple[float, float]
    eye_r: tuple[float, float]
    nose: tuple[float, float]

    @property
    def cx(self) -> float:
        return self.left + self.width / 2.0

    @property
    def eyes_y(self) -> float:
        return (self.eye_l[1] + self.eye_r[1]) / 2.0


@dataclass
class Crop:
    left: int
    top: int
    width: int
    height: int


def detect_face(image: np.ndarray) -> list[Face]:
    """YuNet 검출 — 랜드마크(눈·코)까지 원본 좌표로 돌려준다."""
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
    _, rows = detector.detect(small)
    if rows is None:
        return []
    inv = 1.0 / scale
    faces = []
    for r in rows:
        # YuNet 행: x, y, w, h, 오른눈x,y, 왼눈x,y, 코x,y, 입꼬리들..., 점수
        faces.append(
            Face(
                left=int(r[0] * inv),
                top=int(r[1] * inv),
                width=int(r[2] * inv),
                height=int(r[3] * inv),
                eye_r=(r[4] * inv, r[5] * inv),
                eye_l=(r[6] * inv, r[7] * inv),
                nose=(r[8] * inv, r[9] * inv),
            )
        )
    return faces


# ---- 얼굴 없는 인물 폴백: YOLOX person 검출 ----

_YOLOX_URL = (
    "https://github.com/opencv/opencv_zoo/raw/main/models/"
    "object_detection_yolox/object_detection_yolox_2022nov.onnx"
)
_YOLOX_PATH = Path(__file__).with_name("object_detection_yolox_2022nov.onnx")
_YOLOX_INPUT = 640
_YOLOX_STRIDES = (8, 16, 32)


def _ensure_yolox() -> Path:
    if not _YOLOX_PATH.exists():
        print(f"YOLOX 모델을 내려받는 중... ({_YOLOX_URL})")
        import urllib.request

        urllib.request.urlretrieve(_YOLOX_URL, _YOLOX_PATH)
    return _YOLOX_PATH


def detect_persons(image: np.ndarray) -> list[Box]:
    """COCO person 클래스 박스들 (원본 좌표, 신뢰도순). letterbox 640 + YOLOX raw 디코드."""
    height, width = image.shape[:2]
    ratio = min(_YOLOX_INPUT / width, _YOLOX_INPUT / height)
    resized = cv2.resize(image, (int(width * ratio), int(height * ratio)))
    padded = np.full((_YOLOX_INPUT, _YOLOX_INPUT, 3), 114, dtype=np.uint8)
    padded[: resized.shape[0], : resized.shape[1]] = resized

    net = cv2.dnn.readNet(str(_ensure_yolox()))
    net.setInput(cv2.dnn.blobFromImage(padded))
    pred = net.forward().squeeze(0)  # (N, 85): cx,cy,w,h(raw), obj, 80 cls

    # stride 별 grid 좌표 복원
    grids, strides = [], []
    for s in _YOLOX_STRIDES:
        gy, gx = np.meshgrid(
            np.arange(_YOLOX_INPUT // s), np.arange(_YOLOX_INPUT // s), indexing="ij"
        )
        grids.append(np.stack([gx.ravel(), gy.ravel()], axis=1))
        strides.append(np.full((gx.size, 1), s))
    grid = np.concatenate(grids).astype(np.float32)
    stride = np.concatenate(strides).astype(np.float32)

    cx = (pred[:, 0:1] + grid[:, 0:1]) * stride
    cy = (pred[:, 1:2] + grid[:, 1:2]) * stride
    bw = np.exp(pred[:, 2:3]) * stride
    bh = np.exp(pred[:, 3:4]) * stride
    scores = pred[:, 4] * pred[:, 5]  # objectness × person(클래스 0)

    keep = scores >= PERSON_SCORE_T
    if not keep.any():
        return []
    boxes_xywh = np.concatenate([cx - bw / 2, cy - bh / 2, bw, bh], axis=1)[keep] / ratio
    kept_scores = scores[keep]
    indices = cv2.dnn.NMSBoxes(
        boxes_xywh.tolist(), kept_scores.tolist(), PERSON_SCORE_T, 0.5
    )
    result = []
    for i in np.array(indices).ravel():
        x, y, w, h = boxes_xywh[i]
        result.append(
            Box(
                left=int(max(0, x)),
                top=int(max(0, y)),
                width=int(min(w, width - max(0, x))),
                height=int(min(h, height - max(0, y))),
            )
        )
    result.sort(key=lambda b: b.width * b.height, reverse=True)
    return result


@dataclass
class Box:
    left: int
    top: int
    width: int
    height: int


def dominant_person(persons: list[Box]) -> Box | None:
    """1명이거나, 최대 박스가 2위보다 확실히 클 때만 단독 피사체로 인정한다."""
    if not persons:
        return None
    if len(persons) == 1:
        return persons[0]
    first, second = persons[0], persons[1]
    if first.width * first.height >= PERSON_DOMINANT_RATIO * second.width * second.height:
        return first
    return None


# ---- 중앙 vs 3분할 판정 ----


def yaw_ratio(face: Face) -> float:
    """머리 요 근사 — 코가 눈 중점에서 벗어난 정도 / 눈 간격. 양수 = 화면 오른쪽을 봄."""
    eye_mid_x = (face.eye_l[0] + face.eye_r[0]) / 2.0
    eye_dist = max(abs(face.eye_l[0] - face.eye_r[0]), 1e-6)
    return (face.nose[0] - eye_mid_x) / eye_dist


def background_symmetry(
    image: np.ndarray, subject_cx: float, subject_half_w: float
) -> tuple[float, float]:
    """(엣지 대칭 상관, 엣지 밀도). 인물 영역(중심±반폭)과 그 거울 영역은 제외하고 잰다."""
    height, width = image.shape[:2]
    target_h = 400
    scale = target_h / height
    small = cv2.resize(image, (max(2, int(width * scale)), target_h))
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY).astype(np.float32) / 255.0
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    edge = np.sqrt(gx * gx + gy * gy)
    edge = edge / max(edge.max(), 1e-6)

    body_half_w = subject_half_w * scale
    cx = subject_cx * scale
    x0 = int(max(0, cx - body_half_w))
    x1 = int(min(small.shape[1], cx + body_half_w))
    mask = np.ones(edge.shape, dtype=bool)
    mask[:, x0:x1] = False
    mirror_x0 = small.shape[1] - x1
    mirror_x1 = small.shape[1] - x0
    mask[:, max(0, mirror_x0) : max(0, mirror_x1)] = False

    flipped = edge[:, ::-1]
    a = edge[mask]
    b = flipped[mask]
    if a.size < 100:
        return 0.0, 0.0
    density = float((a > 0.15).mean())
    a = a - a.mean()
    b = b - b.mean()
    denom = float(np.sqrt((a * a).sum() * (b * b).sum()))
    sym = float((a * b).sum() / denom) if denom > 1e-6 else 0.0
    return sym, density


def decide_horizontal(face: Face, sym: float, density: float) -> tuple[float, str]:
    """가로 목표 지점(fx)과 판정 사유를 돌려준다."""
    if sym >= SYM_T and density >= EDGE_DENSITY_T:
        return 0.5, f"center(sym={sym:.2f})"
    yaw = yaw_ratio(face)
    if abs(yaw) < YAW_FRONTAL_T:
        return 0.5, f"center(frontal yaw={yaw:+.2f})"
    # lead room: 보는 방향에 여백 → 오른쪽을 보면 인물은 왼쪽 1/3
    if yaw > 0:
        return 1.0 / 3.0, f"third-left(yaw={yaw:+.2f})"
    return 2.0 / 3.0, f"third-right(yaw={yaw:+.2f})"


# ---- 세로 템플릿 ----


def body_estimate(face: Face) -> tuple[float, float]:
    """(정수리 y, 추정 발끝 y) — 인체 비례 근사."""
    crown = face.top - CROWN_ABOVE_FACE * face.height
    feet = face.top + FEET_PER_FACE_H * face.height
    return crown, feet


def full_shot_crop(
    eyes_y: float,
    subject_cx: float,
    feet_y: float,
    crown_y: float,
    fx: float,
    w: int,
    h: int,
) -> Crop | None:
    """전신 템플릿 — 발밑 여백 3% + 눈높이 상단 1/3. 성립하지 않으면 None."""
    span = feet_y - eyes_y
    if span <= 0:
        return None
    crop_h = span / (1.0 - EYES_FRAC - FEET_GAP_FRAC)
    if crop_h > h or crop_h / h < FULL_MIN_SCALE:
        return None
    crop_w = crop_h * w / h
    top = eyes_y - EYES_FRAC * crop_h
    # 정수리가 잘리면 위로 당긴다 (발밑 여백이 조금 늘어나는 쪽이 머리 잘림보다 낫다)
    top = min(top, crown_y - 0.02 * crop_h)
    top = min(max(top, 0.0), h - crop_h)
    left = min(max(subject_cx - fx * crop_w, 0.0), w - crop_w)
    return Crop(int(left), int(top), int(crop_w), int(crop_h))


def upper_crop(
    anchor_cx: float,
    anchor_cy: float,
    anchor_w: float,
    anchor_h: float,
    crown_y: float,
    feet_y: float,
    fx: float,
    w: int,
    h: int,
) -> tuple[Crop | None, bool, str | None]:
    """상반신 크롭 (앵커=머리 중심을 fx × 상단 1/3 에) + 관절 회피. (크롭, 관절경고, 스킵사유)."""

    def solve(limit: float) -> float:
        s = limit
        s = min(s, anchor_cx / (fx * w))
        s = min(s, (w - anchor_cx) / ((1.0 - fx) * w))
        s = min(s, 3.0 * anchor_cy / h)
        s = min(s, 3.0 * (h - anchor_cy) / (2.0 * h))
        return s

    s = solve(1.0)
    if s < UPPER_MIN_SCALE:
        return None, False, "not_enough_margin"
    if s >= UPPER_NOOP_SCALE:
        return None, False, "already_composed"

    body_h = max(feet_y - crown_y, 1e-6)

    def bottom_frac(scale: float) -> float:
        crop_h = h * scale
        top = min(max(anchor_cy - crop_h / 3.0, 0.0), h - crop_h)
        return (top + crop_h - crown_y) / body_h

    def in_joint_zone(frac: float) -> bool:
        return frac < 1.0 and any(z0 <= frac <= z1 for z0, z1 in JOINT_ZONES)

    joint_warn = False
    if in_joint_zone(bottom_frac(s)):
        # 배율을 조금씩 줄여 관절 구간을 벗어나는 값을 찾는다 (최선 노력)
        adjusted = next(
            (
                s * k
                for k in (0.97, 0.94, 0.91, 0.88)
                if s * k >= UPPER_MIN_SCALE and not in_joint_zone(bottom_frac(s * k))
            ),
            None,
        )
        if adjusted is not None:
            s = adjusted
        else:
            joint_warn = True

    crop_h = h * s
    crop_w = w * s
    if anchor_w > crop_w * MAX_FACE_FRACTION or anchor_h > crop_h * MAX_FACE_FRACTION:
        return None, False, "face_too_dominant"
    top = min(max(anchor_cy - crop_h / 3.0, 0.0), h - crop_h)
    left = min(max(anchor_cx - fx * crop_w, 0.0), w - crop_w)
    return Crop(int(left), int(top), int(crop_w), int(crop_h)), joint_warn, None


# ---- 렌더링 ----


def to_preview(image: np.ndarray) -> np.ndarray:
    height, width = image.shape[:2]
    scale = PREVIEW_HEIGHT / height
    return cv2.resize(image, (max(1, int(width * scale)), PREVIEW_HEIGHT))


def panes(*images: np.ndarray) -> np.ndarray:
    divider = np.full((PREVIEW_HEIGHT, 8, 3), 255, dtype=np.uint8)
    parts: list[np.ndarray] = []
    for image in images:
        if parts:
            parts.append(divider)
        parts.append(to_preview(image))
    return np.hstack(parts)


def annotate(
    image: np.ndarray,
    faces: list[Face],
    crop: Crop | None,
    feet_y: float | None,
    label: str,
    person: Box | None = None,
) -> np.ndarray:
    out = image.copy()
    h, w = image.shape[:2]
    thick = max(2, h // 400)
    if person is not None:
        cv2.rectangle(
            out, (person.left, person.top),
            (person.left + person.width, person.top + person.height), (255, 0, 255), thick,
        )
    for f in faces:
        cv2.rectangle(out, (f.left, f.top), (f.left + f.width, f.top + f.height), (0, 255, 0), thick)
        eyes = int(f.eyes_y)
        cv2.line(out, (max(0, f.left - f.width), eyes), (min(w, f.left + 2 * f.width), eyes), (255, 128, 0), thick)
    if feet_y is not None and 0 <= feet_y < h:
        cv2.line(out, (0, int(feet_y)), (w, int(feet_y)), (0, 0, 255), thick)
    if crop is not None:
        cv2.rectangle(
            out, (crop.left, crop.top),
            (crop.left + crop.width, crop.top + crop.height), (0, 220, 255), thick + 2,
        )
    cv2.putText(
        out, label, (20, h - 30), cv2.FONT_HERSHEY_SIMPLEX,
        h / 1200.0, (255, 255, 255), max(2, h // 350), cv2.LINE_AA,
    )
    return out


def process(path: Path, out_dir: Path) -> str:
    image = cv2.imread(str(path))
    if image is None:
        return "unreadable"
    h, w = image.shape[:2]

    faces = detect_face(image)
    if len(faces) == 1:
        return _process_face(path, out_dir, image, faces[0])
    if len(faces) >= 2:
        reason = f"faces_{len(faces)}"
        compare = panes(image, annotate(image, faces, None, None, reason))
        cv2.imwrite(str(out_dir / f"{path.stem}__SKIP_{reason}.jpg"), compare)
        return reason

    # 얼굴 없음(뒷모습·측면) — person 검출 폴백
    persons = detect_persons(image)
    person = dominant_person(persons)
    if person is None:
        reason = "no_face_no_person" if not persons else f"persons_{len(persons)}"
        compare = panes(image, annotate(image, [], None, None, reason, persons[0] if persons else None))
        cv2.imwrite(str(out_dir / f"{path.stem}__SKIP_{reason}.jpg"), compare)
        return reason
    return _process_body(path, out_dir, image, person)


def _process_face(path: Path, out_dir: Path, image: np.ndarray, face: Face) -> str:
    h, w = image.shape[:2]
    sym, density = background_symmetry(image, face.cx, 1.6 * face.width)
    fx, why = decide_horizontal(face, sym, density)
    crown_y, feet_y = body_estimate(face)

    crop = full_shot_crop(face.eyes_y, face.cx, feet_y, crown_y, fx, w, h)
    vert = "full"
    joint_warn = False
    if crop is None:
        crop, joint_warn, skip = upper_crop(
            face.cx, face.top + face.height / 2.0, face.width, face.height,
            crown_y, feet_y, fx, w, h,
        )
        vert = "upper"
        if crop is None:
            label = f"{skip} | {why} sym={sym:.2f} dens={density:.2f}"
            compare = panes(image, annotate(image, [face], None, feet_y, label))
            cv2.imwrite(str(out_dir / f"{path.stem}__SKIP_{skip}.jpg"), compare)
            return skip or "skip"

    horiz = why.split("(")[0]
    tag = f"{vert}_{horiz}" + ("_joint-warn" if joint_warn else "")
    label = f"{tag} | {why} sym={sym:.2f} dens={density:.2f}"
    cropped = image[crop.top : crop.top + crop.height, crop.left : crop.left + crop.width]
    compare = panes(image, annotate(image, [face], crop, feet_y, label), cropped)
    cv2.imwrite(str(out_dir / f"{path.stem}__CROPPED_{tag}.jpg"), compare)
    return tag


def _process_body(path: Path, out_dir: Path, image: np.ndarray, person: Box) -> str:
    """얼굴 없는 인물 — 몸 bbox 로 같은 템플릿 적용. 발끝은 추정이 아니라 bbox 하단 실측."""
    h, w = image.shape[:2]
    crown_y = float(person.top)
    feet_y = float(person.top + person.height)
    body_h = float(person.height)
    subject_cx = person.left + person.width / 2.0
    eyes_y = crown_y + EYES_FRAC_OF_BODY * body_h

    # 시선이 없으므로: 대칭이면 중앙, 아니면 가까운 3분할선
    sym, density = background_symmetry(image, subject_cx, 0.8 * person.width)
    if sym >= SYM_T and density >= EDGE_DENSITY_T:
        fx, why = 0.5, f"center(sym={sym:.2f})"
    elif subject_cx <= w / 2.0:
        fx, why = 1.0 / 3.0, "third-left(no gaze, nearest)"
    else:
        fx, why = 2.0 / 3.0, "third-right(no gaze, nearest)"

    crop = full_shot_crop(eyes_y, subject_cx, feet_y, crown_y, fx, w, h)
    vert = "full-body"
    joint_warn = False
    if crop is None:
        # 머리 앵커 근사: 몸 상단 13% 영역
        head_h = 0.13 * body_h
        crop, joint_warn, skip = upper_crop(
            subject_cx, crown_y + head_h / 2.0, 0.6 * person.width, head_h,
            crown_y, feet_y, fx, w, h,
        )
        vert = "upper-body"
        if crop is None:
            label = f"{skip} | {why} sym={sym:.2f} dens={density:.2f}"
            compare = panes(image, annotate(image, [], None, feet_y, label, person))
            cv2.imwrite(str(out_dir / f"{path.stem}__SKIP_{skip}.jpg"), compare)
            return skip or "skip"

    horiz = why.split("(")[0]
    tag = f"{vert}_{horiz}" + ("_joint-warn" if joint_warn else "")
    label = f"{tag} | {why} sym={sym:.2f} dens={density:.2f}"
    cropped = image[crop.top : crop.top + crop.height, crop.left : crop.left + crop.width]
    compare = panes(image, annotate(image, [], crop, feet_y, label, person), cropped)
    cv2.imwrite(str(out_dir / f"{path.stem}__CROPPED_{tag}.jpg"), compare)
    return tag


def main() -> int:
    parser = argparse.ArgumentParser(description="구도 크롭 실험실 (PC 전용)")
    parser.add_argument("input_dir", type=Path)
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args()

    input_dir: Path = args.input_dir
    if not input_dir.is_dir():
        print(f"입력 폴더가 없습니다: {input_dir}")
        return 1
    out_dir: Path = args.out or input_dir.parent / f"{input_dir.name}_lab"
    out_dir.mkdir(parents=True, exist_ok=True)

    photos = sorted(p for p in input_dir.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES)
    if not photos:
        print(f"입력 폴더에 사진이 없습니다: {input_dir}")
        return 1

    counts: dict[str, int] = {}
    for path in photos:
        tag = process(path, out_dir)
        counts[tag] = counts.get(tag, 0) + 1
        print(f"{path.name}: {tag}")
    print(f"\n총 {len(photos)}장 — " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    print(f"비교 결과: {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
