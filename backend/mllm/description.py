# backend/mllm/description.py
"""촬영 직후 대표 컷 한 장을 Haiku로 빠르게 설명한다 — 무거운 후보 비교(Opus)와 병렬로 돌아
사용자가 결과를 기다리는 동안 먼저 "무엇이 찍혔는지"를 들려주기 위한 모듈이다 (#76)."""

from __future__ import annotations

import base64
import json
from pathlib import Path

from anthropic import Anthropic, APIConnectionError, APIStatusError
from pydantic import BaseModel
from dotenv import load_dotenv

from backend.mllm.client import _downscaled_jpeg
from backend.storage.frame_buffer import CAPTURES_DIR, load_session_frame_paths
from backend.utils.logger import load_logger

load_dotenv()

logger = load_logger("mllm_description.log")

MODEL_ID = "claude-haiku-4-5-20251001"
MAX_TOKENS = 400
DESCRIPTION_FILENAME = "description.json"

SYSTEM_PROMPT = (
    "시각장애 사용자가 방금 찍은 사진을 들려주는 역할이다. 2~3문장, 존댓말로 설명한다. "
    "첫 문장은 주인공(무엇이 어디에 어떤 모습으로)을, 다음 문장은 주변과 배경을, "
    "여유가 되면 색감·분위기를 덧붙인다. 눈이 보이지 않아도 장면이 그려지게 구체적으로 말하되, "
    "확실하지 않은 세부는 지어내지 않는다. "
    "모든 문장은 반드시 '~있어요', '~이에요', '~보여요'처럼 부드러운 존댓말로 끝낸다. 반말 금지."
)


def trigger_description(session_id: str) -> None:
    """세션 대표 컷의 한 줄 설명을 생성해 저장한다. 실패해도 예외를 밖으로 흘리지 않는다."""
    try:
        representative, _ = load_session_frame_paths(session_id)
        text = describe_photo(representative)
    except Exception as exc:  # noqa: BLE001 - 설명 실패가 촬영·비교 흐름을 막아서는 안 된다
        logger.error(f"세션 {session_id}: 사진 설명 생성 실패 — {exc}")
        text = None

    save_description(session_id, text)
    logger.info(f"세션 {session_id}: 사진 설명 저장 완료 — {text}")


def describe_photo(image_path: Path) -> str | None:
    """대표 컷 한 장을 한 문장으로 설명한다. 호출 실패 시 None."""
    client = Anthropic()
    data = base64.standard_b64encode(_downscaled_jpeg(image_path)).decode("utf-8")
    try:
        response = client.messages.create(
            model=MODEL_ID,
            max_tokens=MAX_TOKENS,
            system=SYSTEM_PROMPT,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "이 사진을 설명해줘:"},
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


def save_description(session_id: str, text: str | None) -> Path:
    """설명(실패 시 null)을 세션 디렉터리에 저장한다 — 파일 존재 자체가 '생성 시도 완료' 신호다."""
    session_dir = CAPTURES_DIR / session_id
    session_dir.mkdir(parents=True, exist_ok=True)
    path = session_dir / DESCRIPTION_FILENAME
    path.write_text(json.dumps({"description": text}, ensure_ascii=False), encoding="utf-8")
    return path


def load_description(session_id: str) -> dict | None:
    """저장된 설명을 읽어 반환한다. 아직 없으면 None."""
    path = CAPTURES_DIR / session_id / DESCRIPTION_FILENAME
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
