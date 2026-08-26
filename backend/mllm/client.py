# backend/mllm/client.py
"""설계된 프롬프트(prompts.py)로 Claude를 호출해 대표 컷과 후보 프레임을 비교하는 프로덕션 클라이언트."""

from __future__ import annotations

import base64
import io
from pathlib import Path

from anthropic import Anthropic, APIConnectionError, APIStatusError
from dotenv import load_dotenv
from PIL import Image, ImageOps, UnidentifiedImageError

from backend.mllm.prompts import SYSTEM_PROMPT, FrameComparisonResult, build_comparison_prompt
from backend.utils.logger import load_logger

load_dotenv()

logger = load_logger("mllm_client.log")

MODEL_ID = "claude-haiku-4-5-20251001"
MAX_TOKENS = 1024
# Anthropic 권장 최대 변(1568px) — 이보다 크면 서버가 어차피 축소하므로 원본 전송은 시간 낭비 (#76)
MAX_IMAGE_DIM = 1568


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
    """이미지를 필요 시 축소한 뒤 base64로 인코딩해 Anthropic 메시지 콘텐츠 블록으로 만든다."""
    data = base64.standard_b64encode(_downscaled_jpeg(path)).decode("utf-8")
    return {
        "type": "image",
        "source": {"type": "base64", "media_type": "image/jpeg", "data": data},
    }


def _downscaled_jpeg(path: Path) -> bytes:
    """EXIF 회전을 픽셀에 적용하고, 긴 변이 [MAX_IMAGE_DIM]을 넘으면 그 크기로 줄여 JPEG 재인코딩한다.

    카메라 JPEG 는 픽셀을 센서 방향(가로)으로 두고 EXIF Orientation 으로만 회전을 기록한다. 재인코딩
    하면서 EXIF 가 사라지면 모델이 누운 사진을 보게 되고, 앱이 보낸 정규화 bbox 위치("왼쪽 위")와도
    어긋난다 (2026-08-23). 회전도 축소도 필요 없으면 원본 그대로.

    디코딩할 수 없는 바이트면 원본을 반환한다 — 축소는 비용 최적화일 뿐이라,
    이것 때문에 MLLM 호출 자체가 죽으면 안 된다 (판정은 API 쪽 검증에 맡긴다).
    """
    raw = path.read_bytes()
    try:
        with Image.open(io.BytesIO(raw)) as img:
            rotated = _has_orientation_tag(img)
            if not rotated and max(img.size) <= MAX_IMAGE_DIM:
                return raw
            upright = ImageOps.exif_transpose(img) if rotated else img
            upright.thumbnail((MAX_IMAGE_DIM, MAX_IMAGE_DIM))
            buffer = io.BytesIO()
            upright.convert("RGB").save(buffer, format="JPEG", quality=85)
            return buffer.getvalue()
    except UnidentifiedImageError:
        return raw


def _has_orientation_tag(img: Image.Image) -> bool:
    """EXIF Orientation 이 1(정상) 이외의 값인지 — 회전·반전이 필요한 JPEG 인지 판정한다."""
    try:
        return img.getexif().get(0x0112, 1) != 1
    except Exception:  # noqa: BLE001 - EXIF 파싱 실패는 회전 없음으로 본다
        return False
