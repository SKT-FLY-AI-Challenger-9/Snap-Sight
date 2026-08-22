# backend/mllm/metadata.py
"""촬영 사진의 검색용 상세 메타데이터(상세 설명 + 폐쇄형 라벨)를 생성·저장한다.

docs/feature-expansion-plan.md 기능 3-B — 즉시 낭독용 2문장 설명(description.py)과 별개로,
갤러리 검색을 위해 느려도 정확한 메타데이터를 만든다:
 - long_description: "자세히 들려줘" 낭독용 상세 문단
 - labels: 고정 사전(ai/taxonomy/photo_labels.json)에서만 고른 라벨 id — 검색과 어휘를 공유
 - custom_labels: 앱이 보낸 사용자 커스텀 라벨 중 이 사진에 해당하는 것

시간·장소 등 필수 메타데이터는 LLM 과 무관하게 앱이 로컬에 저장한다 (여기 책임 아님).
"""

from __future__ import annotations

import base64
import json
from pathlib import Path

from anthropic import Anthropic, APIConnectionError, APIStatusError
from dotenv import load_dotenv
from pydantic import BaseModel, Field

from ai.photo_labels import PhotoLabelTaxonomy, default_photo_labels
from backend.mllm.client import _downscaled_jpeg
from backend.storage.frame_buffer import CAPTURES_DIR, load_session_frame_paths
from backend.utils.logger import load_logger

load_dotenv()

logger = load_logger("mllm_metadata.log")

# 지연 허용(비동기 온디맨드)이므로 검색 품질을 위해 상위 모델을 쓴다 — 비교 판정과 동일.
MODEL_ID = "claude-opus-5"
MAX_TOKENS = 2000
METADATA_FILENAME = "metadata.json"

SYSTEM_PROMPT = (
    "시각장애 사용자의 사진을 나중에 음성으로 검색·낭독할 수 있게 정리하는 역할이다.\n"
    "두 가지를 만든다.\n"
    "1. long_description — 사진을 처음 듣는 사람에게 들려주는 상세 설명 문단(3~6문장, 존댓말). "
    "주인공의 모습·표정·옷차림, 주변 사물, 배경과 분위기를 담되 확실하지 않은 세부는 지어내지 않는다.\n"
    "2. 라벨 — 반드시 '고를 수 있는 라벨 목록'의 id 중에서만 고른다. 사진에 실제로 해당하는 것만, "
    "없으면 빈 배열. 목록에 없는 라벨을 만들어내면 안 된다.\n"
    "사용자 커스텀 라벨 목록이 주어지면, 그중 이 사진에 명백히 해당하는 것만 custom_labels 로 고른다. "
    "확실하지 않으면 고르지 않는다.\n"
    "people_count 는 사진 속에 뚜렷이 보이는 사람 수다. 사람이 없으면 0."
)

_USER_PROMPT_TEMPLATE = """## 고를 수 있는 라벨 목록 (labels 는 반드시 이 id 중에서만)
{label_catalog}

## 사용자 커스텀 라벨 목록 (custom_labels 는 이 목록에 있는 이름 그대로만, 해당할 때만)
{custom_labels}

## 참고 정보
- 촬영 시 발화: {raw_text}
- 온디바이스 검출 객체: {detected_objects}

이 사진의 long_description 과 라벨을 만들어줘."""


class PhotoMetadataOutput(BaseModel):
    """LLM 출력 스키마. labels 는 저장 전에 사전 대조로 한 번 더 검증한다."""

    long_description: str
    labels: list[str] = Field(default_factory=list)
    custom_labels: list[str] = Field(default_factory=list)
    people_count: int | None = None


def trigger_metadata(
    session_id: str,
    raw_text: str = "",
    custom_labels: list[str] | None = None,
    detected_objects: list[str] | None = None,
) -> None:
    """세션 대표 컷의 검색용 메타데이터를 생성해 저장한다. 실패해도 예외를 밖으로 흘리지 않는다.

    실패 시에도 null 필드로 파일을 저장한다 — 파일 존재가 '생성 시도 완료' 신호라서,
    조회 API 가 영원히 pending 을 돌려주는 일을 막는다 (description.py 와 같은 규약).
    """
    taxonomy = default_photo_labels()
    try:
        representative, _ = load_session_frame_paths(session_id)
        result = generate_metadata(
            representative,
            raw_text=raw_text,
            custom_labels=custom_labels or [],
            detected_objects=detected_objects or [],
            taxonomy=taxonomy,
        )
    except Exception as exc:  # noqa: BLE001 - 메타데이터 실패가 촬영·비교 흐름을 막아서는 안 된다
        logger.error(f"세션 {session_id}: 메타데이터 생성 실패 — {exc}")
        result = None

    save_metadata(session_id, result, taxonomy, custom_labels or [])
    logger.info(f"세션 {session_id}: 메타데이터 저장 완료 — {result}")


def generate_metadata(
    image_path: Path,
    *,
    raw_text: str,
    custom_labels: list[str],
    detected_objects: list[str],
    taxonomy: PhotoLabelTaxonomy,
) -> PhotoMetadataOutput | None:
    """대표 컷 한 장의 상세 설명·라벨을 생성한다. 호출 실패 시 None."""
    client = Anthropic()
    data = base64.standard_b64encode(_downscaled_jpeg(image_path)).decode("utf-8")
    user_prompt = build_user_prompt(
        raw_text=raw_text,
        custom_labels=custom_labels,
        detected_objects=detected_objects,
        taxonomy=taxonomy,
    )
    try:
        response = client.messages.parse(
            model=MODEL_ID,
            max_tokens=MAX_TOKENS,
            system=SYSTEM_PROMPT,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": user_prompt},
                        {
                            "type": "image",
                            "source": {"type": "base64", "media_type": "image/jpeg", "data": data},
                        },
                    ],
                }
            ],
            output_format=PhotoMetadataOutput,
        )
    except (APIConnectionError, APIStatusError) as exc:
        logger.error(f"메타데이터 API 호출 실패: {exc}")
        return None
    return response.parsed_output


def build_user_prompt(
    *,
    raw_text: str,
    custom_labels: list[str],
    detected_objects: list[str],
    taxonomy: PhotoLabelTaxonomy,
) -> str:
    return _USER_PROMPT_TEMPLATE.format(
        label_catalog=taxonomy.prompt_catalog(),
        custom_labels=", ".join(custom_labels) if custom_labels else "(없음)",
        raw_text=raw_text.strip() or "(없음)",
        detected_objects=", ".join(detected_objects) if detected_objects else "(없음)",
    )


def save_metadata(
    session_id: str,
    result: PhotoMetadataOutput | None,
    taxonomy: PhotoLabelTaxonomy,
    requested_custom_labels: list[str],
) -> Path:
    """메타데이터(실패 시 null 필드)를 세션 디렉터리에 저장한다.

    저장 전에 폐쇄형 계약을 강제한다:
     - labels: 고정 사전에 실존하는 id 만
     - custom_labels: 앱이 보낸 목록에 있던 이름만 (LLM 창작 차단)
    """
    session_dir = CAPTURES_DIR / session_id
    session_dir.mkdir(parents=True, exist_ok=True)
    path = session_dir / METADATA_FILENAME

    if result is None:
        payload = {
            "taxonomy_version": taxonomy.version,
            "long_description": None,
            "labels": [],
            "custom_labels": [],
            "people_count": None,
        }
    else:
        allowed_custom = set(requested_custom_labels)
        payload = {
            "taxonomy_version": taxonomy.version,
            "long_description": result.long_description.strip() or None,
            "labels": taxonomy.validate_label_ids(result.labels),
            "custom_labels": [name for name in result.custom_labels if name in allowed_custom],
            "people_count": result.people_count,
        }

    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return path


def load_metadata(session_id: str) -> dict | None:
    """저장된 메타데이터를 읽어 반환한다. 아직 없으면 None."""
    path = CAPTURES_DIR / session_id / METADATA_FILENAME
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))
