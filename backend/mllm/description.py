# backend/mllm/description.py
"""Description persistence plus legacy/standalone photo-label model helpers.

The capture API no longer runs the Haiku description in parallel with frame
selection. Its canonical pipeline stores the brief description produced by the
same structured final-image understanding as detailed metadata.
"""

from __future__ import annotations

import base64
import json
from pathlib import Path

from anthropic import Anthropic, APIConnectionError, APIStatusError
from dotenv import load_dotenv
from pydantic import BaseModel

from backend.mllm.client import _downscaled_jpeg
from backend.storage.atomic import atomic_write_text
from backend.storage.frame_buffer import load_session_frame_paths, session_dir_for
from backend.utils.logger import load_logger

load_dotenv()

logger = load_logger("mllm_description.log")

MODEL_ID = "claude-haiku-4-5-20251001"
MAX_TOKENS = 400
DESCRIPTION_FILENAME = "description.json"

SYSTEM_PROMPT = (
    "시각장애 사용자가 방금 찍은 사진을 들려주는 역할이다. 정확히 1문장, 존댓말로 아주 짧게 설명한다. "
    "무엇이 찍혔는지(주인공이 어디에 어떤 모습으로)만 한 문장에 담는다 — 낭독했을 때 한 줄 안에 끝나야 한다. "
    "세부를 나열하지 말고 핵심만 고르되, 확실하지 않은 세부는 지어내지 않는다. "
    "등록 대상의 local_* 참조 토큰은 낭독 직전에 기기에서 실제 이름으로 치환된다 — 이름 자리에 "
    "토큰을 그대로 쓴다. bbox 위치의 대상이 보이면(사진·화면 속 인물이어도) 일반 명사 대신 "
    "토큰으로 지칭하는 것이 기본이고, 전혀 보이지 않을 때만 쓰지 않는다. 토큰의 실제 이름이나 "
    "관계를 추측하지 않고, 관계를 알 수 없는 사람을 친구·가족이라고 부르지 않는다. "
    "'촬영 의도 대상'으로 표시된 등록 대상이 사진에서 분명히 보이면 그 대상을 문장의 주인공으로 "
    "삼는다 — 사용자가 찍으려던 대상이기 때문이다. 보이지 않으면 주인공으로 단정하지 않는다. "
    "문장은 반드시 '~있어요', '~이에요', '~보여요'처럼 부드러운 존댓말로 끝낸다. 반말 금지."
)


def trigger_description(session_id: str, known_subjects: list[dict] | None = None) -> None:
    """세션 대표 컷의 한 줄 설명을 생성해 저장한다. 실패해도 예외를 밖으로 흘리지 않는다.

    known_subjects: 앱이 셔터 순간 식별한 등록 인물·사물 [{name, kind, bbox?}] — 이름으로 부르게 한다.
    """
    try:
        representative, _ = load_session_frame_paths(session_id)
        text = describe_photo(representative, known_subjects or [])
    except Exception as exc:  # noqa: BLE001 - 설명 실패가 촬영·비교 흐름을 막아서는 안 된다
        logger.error(f"세션 {session_id}: 사진 설명 생성 실패 — {exc}")
        text = None

    save_description(session_id, text)
    logger.info(f"세션 {session_id}: 사진 설명 저장 완료 — {text}")


def describe_photo(image_path: Path, known_subjects: list[dict] | None = None) -> str | None:
    """대표 컷 한 장을 한 문장으로 설명한다. 호출 실패 시 None."""
    client = Anthropic()
    data = base64.standard_b64encode(_downscaled_jpeg(image_path)).decode("utf-8")
    prompt = "이 사진을 설명해줘:"
    subjects_text = format_known_subjects(known_subjects or [])
    if subjects_text:
        prompt = f"사진 속 등록된 인물·사물:\n{subjects_text}\n\n이 사진을 설명해줘:"
    try:
        response = client.messages.create(
            model=MODEL_ID,
            max_tokens=MAX_TOKENS,
            temperature=0.2, # 토큰 사용 일관성 (metadata.py 와 동일 근거)
            system=SYSTEM_PROMPT,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {
                            "type": "image",
                            "source": {"type": "base64", "media_type": "image/jpeg", "data": data},
                        },
                    ],
                }
            ],
        )
    except (APIConnectionError, APIStatusError) as exc:
        logger.error(f"사진 설명 API 호출 실패: {exc}")
        return None

    text = "".join(block.text for block in response.content if block.type == "text").strip()
    return text or None


def format_known_subjects(known_subjects: list[dict]) -> str:
    """등록 인물·사물 목록을 프롬프트용 줄글로 — 이름(종류, 화면 속 대략 위치). 위치는 bbox 중심으로 9분할."""
    lines = []
    for subject in known_subjects:
        identifier = str(subject.get("name") or subject.get("subject_ref") or "").strip()
        if not identifier:
            continue
        if subject.get("named") is False:
            # 이름 매핑이 없는 참조 — 토큰을 프롬프트에 노출하지 않고 역할로만 알린다.
            # 모델은 이 대상을 보이는 대로(일반 명사) 지칭하며 중심으로 서술한다.
            identifier = "요청한 촬영 대상"
        kind = "사람" if subject.get("kind") == "person" else "사물"
        position = describe_position(subject.get("bbox"))
        # 9분할 단어만으로는 나란히 놓인 같은 종류 대상들을 구분 못 한다 (2026-08-23 실기기:
        # 키티 인형의 옷을 오리 인형 토큰에 붙임) — 정확한 좌표 범위를 함께 준다.
        coords = describe_bbox_range(subject.get("bbox"))
        detail = ", ".join(part for part in (position, coords) if part)
        marker = " — 촬영 의도 대상" if subject.get("intent_target") else ""
        lines.append(f"- {identifier} ({kind}{', ' + detail if detail else ''}){marker}")
    return "\n".join(lines)


def describe_bbox_range(bbox: dict | None) -> str | None:
    """정규화 bbox를 '가로 12~34% · 세로 40~70%' 형태로 — 토큰↔대상 대응의 정밀 근거."""
    if not bbox:
        return None
    try:
        x_min, x_max = float(bbox["x_min"]), float(bbox["x_max"])
        y_min, y_max = float(bbox["y_min"]), float(bbox["y_max"])
    except (KeyError, TypeError, ValueError):
        return None
    return (
        f"가로 {round(x_min * 100)}~{round(x_max * 100)}% · "
        f"세로 {round(y_min * 100)}~{round(y_max * 100)}%"
    )


def describe_position(bbox: dict | None) -> str | None:
    """정규화 bbox(0..1) 의 중심을 '왼쪽 위'처럼 9분할 위치 말로 바꾼다. 없으면 None."""
    if not bbox:
        return None
    try:
        cx = (float(bbox["x_min"]) + float(bbox["x_max"])) / 2
        cy = (float(bbox["y_min"]) + float(bbox["y_max"])) / 2
    except (KeyError, TypeError, ValueError):
        return None
    horizontal = "왼쪽" if cx < 1 / 3 else "오른쪽" if cx > 2 / 3 else "가운데"
    vertical = "위" if cy < 1 / 3 else "아래" if cy > 2 / 3 else ""
    return f"화면 {horizontal}{' ' + vertical if vertical else ''}".strip()


def save_description(
    session_id: str,
    text: str | None,
    *,
    capture_revision: int | None = None,
    final_frame_id: str | None = None,
) -> Path:
    """설명(실패 시 null)을 세션 디렉터리에 저장한다 — 파일 존재 자체가 '생성 시도 완료' 신호다."""
    session_dir = session_dir_for(session_id)
    session_dir.mkdir(parents=True, exist_ok=True)
    path = session_dir / DESCRIPTION_FILENAME
    payload = {
        "description": text,
        "capture_revision": capture_revision,
        "final_frame_id": final_frame_id,
    }
    atomic_write_text(path, json.dumps(payload, ensure_ascii=False))
    return path


def load_description(session_id: str) -> dict | None:
    """저장된 설명을 읽어 반환한다. 아직 없으면 None."""
    path = session_dir_for(session_id) / DESCRIPTION_FILENAME
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


LABEL_SYSTEM_PROMPT = (
    "시각장애 사용자의 사진첩을 정리하는 역할이다. 사진을 보고 category, label, description을 만든다. "
    "category는 반드시 '인물'(사람이 주인공), '음식'(음식·음료가 주인공), '추억'(그 외 풍경·물건·장소) 중 하나다. "
    "label은 '장소·피사체' 꼴의 12자 이내 요약(예: '바닷가·친구 2명', '카페·커피'), "
    "description은 한두 문장의 존댓말 설명이다. 확실하지 않은 세부는 지어내지 않는다."
)

# 대분류는 앱 필터와 1:1 — 모델이 다른 값을 내면 '추억'으로 정규화한다
PHOTO_CATEGORIES = ("인물", "음식", "추억")


class PhotoLabel(BaseModel):
    """사진첩 카드용 카테고리+라벨+설명 응답 스키마."""

    category: str
    label: str
    description: str


def label_photo_bytes(image_bytes: bytes) -> PhotoLabel | None:
    """사진 바이트를 받아 사진첩용 라벨·설명을 생성한다. 실패 시 None."""
    client = Anthropic()
    data = base64.standard_b64encode(image_bytes).decode("utf-8")
    try:
        response = client.messages.parse(
            model=MODEL_ID,
            max_tokens=MAX_TOKENS,
            system=LABEL_SYSTEM_PROMPT,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "이 사진의 label과 description을 만들어줘:"},
                        {
                            "type": "image",
                            "source": {"type": "base64", "media_type": "image/jpeg", "data": data},
                        },
                    ],
                }
            ],
            output_format=PhotoLabel,
        )
    except (APIConnectionError, APIStatusError) as exc:
        logger.error(f"사진 라벨링 API 호출 실패: {exc}")
        return None
    result = response.parsed_output
    if result is not None and result.category not in PHOTO_CATEGORIES:
        result = result.model_copy(update={"category": "추억"})
    return result
