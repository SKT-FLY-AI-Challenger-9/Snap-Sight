# backend/mllm/client.py
"""설계된 프롬프트(prompts.py)로 Claude를 호출해 대표 컷과 후보 프레임을 비교하는 프로덕션 클라이언트."""

from __future__ import annotations

import base64
from pathlib import Path

from anthropic import Anthropic, APIConnectionError, APIStatusError
from dotenv import load_dotenv

from backend.mllm.prompts import SYSTEM_PROMPT, FrameComparisonResult, build_comparison_prompt
from backend.utils.logger import load_logger

load_dotenv()

logger = load_logger("mllm_client.log")

MODEL_ID = "claude-opus-5"
MAX_TOKENS = 1024


class MllmClientError(Exception):
    """MLLM 호출 실패 또는 응답 검증 실패 시 발생한다."""


def compare_candidate_frames(
    raw_text: str,
    structured_requirements: dict[str, str],
    representative_frame: Path,
    candidate_frames: list[Path],
    candidate_scores: list[dict] | None = None,
) -> FrameComparisonResult:
    """대표 컷과 후보 프레임을 ③이 설계한 프롬프트로 비교하고 판정 결과를 반환한다."""
    client = Anthropic()
    prompt = build_comparison_prompt(raw_text, structured_requirements, candidate_scores)
    image_content = _build_image_content(representative_frame, candidate_frames)

    try:
        response = client.messages.parse(
            model=MODEL_ID,
            max_tokens=MAX_TOKENS,
            system=SYSTEM_PROMPT,
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
    """messages.parse() 응답에서 파싱된 결과를 꺼내고, 비어 있으면 예외를 발생시킨다."""
    result = response.parsed_output
    if result is None:
        raise ValueError("MLLM 응답 파싱 실패 — parsed_output이 비어 있음")
    return result


def _fallback_result(reason: str) -> FrameComparisonResult:
    """호출·검증 실패 시 대표 컷을 유지하는 안전한 기본 결과를 만든다."""
    return FrameComparisonResult(improved=False, selected_frame=None, reason=reason)


def _build_image_content(representative_frame: Path, candidate_frames: list[Path]) -> list[dict]:
    """대표 컷과 후보 프레임들을 SYSTEM_PROMPT가 기대하는 candidate_N 라벨로 변환한다."""
    content = [{"type": "text", "text": "대표 컷:"}, _encode_image(representative_frame)]
    for index, frame in enumerate(candidate_frames, start=1):
        content.append({"type": "text", "text": f"candidate_{index}:"})
        content.append(_encode_image(frame))
    return content


def _encode_image(path: Path) -> dict:
    """이미지 파일을 base64로 인코딩해 Anthropic 메시지 콘텐츠 블록으로 만든다."""
    data = base64.standard_b64encode(path.read_bytes()).decode("utf-8")
    media_type = "image/jpeg" if path.suffix.lower() in (".jpg", ".jpeg") else "image/png"
    return {
        "type": "image",
        "source": {"type": "base64", "media_type": media_type, "data": data},
    }
