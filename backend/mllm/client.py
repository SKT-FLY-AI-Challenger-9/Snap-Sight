# backend/mllm/client.py

from __future__ import annotations

import base64
from pathlib import Path

from anthropic import Anthropic, APIConnectionError, APIStatusError
from dotenv import load_dotenv
from pydantic import BaseModel, model_validator

from backend.utils.logger import load_logger

load_dotenv()

logger = load_logger(__name__)

MODEL_ID = "claude-opus-5"
MAX_TOKENS = 1024


class FrameComparisonResult(BaseModel):
    improved: bool
    selected_frame: str | None
    reason: str

    @model_validator(mode="after")
    def validate_selected_frame(self) -> "FrameComparisonResult":
        if self.improved and self.selected_frame is None:
            raise ValueError("improved=true인데 selected_frame이 없습니다")
        if not self.improved and self.selected_frame is not None:
            raise ValueError("improved=false인데 selected_frame이 있습니다")
        return self


class MllmClientError(Exception):
    """MLLM 호출 실패 또는 응답 검증 실패 시 발생한다."""


def compare_candidate_frames(
    prompt: str,
    representative_frame: Path,
    candidate_frames: list[Path],
) -> FrameComparisonResult:
    client = Anthropic()
    image_content = _build_image_content(representative_frame, candidate_frames)

    try:
        response = client.messages.parse(
            model=MODEL_ID,
            max_tokens=MAX_TOKENS,
            messages=[
                {
                    "role": "user",
                    "content": [{"type": "text", "text": prompt}, *image_content],
                }
            ],
            output_format=FrameComparisonResult,
        )
    except (APIConnectionError, APIStatusError) as exc:
        logger.error(f"MLLM API 호출 실패: {exc}")
        return _fallback_result(f"MLLM 호출 실패로 대표 컷 유지: {exc}")

    try:
        return _parse_result(response)
    except ValueError as exc:
        logger.error(f"MLLM 응답 검증 실패: {exc}")
        return _fallback_result(f"MLLM 응답 불일치로 대표 컷 유지: {exc}")


def _parse_result(response) -> FrameComparisonResult:
    result = response.parsed_output
    if result is None:
        raise ValueError("MLLM 응답 파싱 실패 — parsed_output이 비어 있음")
    return result


def _fallback_result(reason: str) -> FrameComparisonResult:
    return FrameComparisonResult(improved=False, selected_frame=None, reason=reason)


def _build_image_content(
    representative_frame: Path, candidate_frames: list[Path]
) -> list[dict]:
    content = [{"type": "text", "text": "대표 컷:"}, _encode_image(representative_frame)]
    for index, frame in enumerate(candidate_frames):
        content.append({"type": "text", "text": f"후보 {index}:"})
        content.append(_encode_image(frame))
    return content


def _encode_image(path: Path) -> dict:
    data = base64.standard_b64encode(path.read_bytes()).decode("utf-8")
    media_type = "image/jpeg" if path.suffix.lower() in (".jpg", ".jpeg") else "image/png"
    return {
        "type": "image",
        "source": {"type": "base64", "media_type": media_type, "data": data},
    }
