# backend/mllm/model_comparison.py
"""여러 MLLM/VLM 후보의 프레임 비교 성능(지연시간·응답 스키마 유효성)을 CLI로 비교하는 스크립트."""

from __future__ import annotations

import argparse
import base64
import json
import os
import time
from dataclasses import dataclass
from pathlib import Path

import anthropic
import openai
from dotenv import load_dotenv

from backend.utils.logger import load_logger

load_dotenv()

logger = load_logger("model_comparison.log")

COMPARISON_PROMPT = (
    "대표 컷과 후보 프레임들을 비교해서, 표정·눈감음·의도 부합성 기준으로 "
    "더 나은 프레임이 있는지 판단하라. 반드시 아래 JSON 형식으로만 답하라:\n"
    '{"improved": bool, "selected_frame": "후보 라벨 또는 null", "reason": "판단 근거"}'
)


@dataclass
class ModelCandidate:
    """비교 대상 MLLM/VLM 모델 하나를 호출하는 데 필요한 정보."""

    name: str
    provider: str  # "anthropic" | "openai_compatible"
    model_id: str
    api_key_env: str
    base_url: str | None = None  # openai_compatible일 때만 사용


# model_id는 호스팅 플랫폼 카탈로그가 자주 바뀌므로 실행 전 Together.ai/Fireworks.ai에서 현재 값 확인 필요
MODEL_CANDIDATES: list[ModelCandidate] = [
    ModelCandidate(
        name="Claude Opus 5",
        provider="anthropic",
        model_id="claude-opus-5",
        api_key_env="ANTHROPIC_API_KEY",
    ),
    ModelCandidate(
        name="Claude Sonnet 5",
        provider="anthropic",
        model_id="claude-sonnet-5",
        api_key_env="ANTHROPIC_API_KEY",
    ),
    ModelCandidate(
        name="Claude Haiku 4.5",
        provider="anthropic",
        model_id="claude-haiku-4-5-20251001",
        api_key_env="ANTHROPIC_API_KEY",
    ),
    ModelCandidate(
        name="Qwen2.5-VL-72B (Together)",
        provider="openai_compatible",
        model_id="Qwen/Qwen2.5-VL-72B-Instruct",
        base_url="https://api.together.xyz/v1",
        api_key_env="TOGETHER_API_KEY",
    ),
    ModelCandidate(
        name="Llama 3.2 90B Vision (Together)",
        provider="openai_compatible",
        model_id="meta-llama/Llama-3.2-90B-Vision-Instruct-Turbo",
        base_url="https://api.together.xyz/v1",
        api_key_env="TOGETHER_API_KEY",
    ),
    ModelCandidate(
        name="Gemini 2.5 Flash",
        provider="openai_compatible",
        model_id="gemini-2.5-flash",
        base_url="https://generativelanguage.googleapis.com/v1beta/openai/",
        api_key_env="GEMINI_API_KEY",
    ),
]


@dataclass
class ComparisonRun:
    """모델 한 번 호출한 결과 — 지연시간·원본 응답·스키마 유효성을 담는다."""

    model_name: str
    latency_seconds: float
    raw_output: str
    valid_json: bool
    error: str | None = None


def run_comparison(
    representative_frame: Path,
    candidate_frames: list[Path],
    models: list[ModelCandidate] | None = None,
) -> list[ComparisonRun]:
    """주어진 프레임들로 후보 모델 전부(또는 지정된 모델들)를 순서대로 호출해 결과를 모은다."""
    models = models if models is not None else MODEL_CANDIDATES
    items = _build_frame_items(representative_frame, candidate_frames)
    return [_run_single_model(model, items) for model in models]


def _run_single_model(model: ModelCandidate, items: list[tuple[str, Path]]) -> ComparisonRun:
    """모델 하나를 호출하고 지연시간·성공 여부를 측정해 ComparisonRun으로 반환한다."""
    start = time.monotonic()
    try:
        raw_output = (
            _call_anthropic(model, items)
            if model.provider == "anthropic"
            else _call_openai_compatible(model, items)
        )
    # This benchmark must record one provider failure and continue with the
    # remaining models, including unexpected third-party SDK exceptions.
    except Exception as exc:  # noqa: BLE001
        latency = time.monotonic() - start
        logger.error(f"{model.name} 호출 실패: {exc}")
        return ComparisonRun(model.name, latency, "", False, error=str(exc))

    latency = time.monotonic() - start
    return ComparisonRun(model.name, latency, raw_output, _looks_like_valid_schema(raw_output))


def _call_anthropic(model: ModelCandidate, items: list[tuple[str, Path]]) -> str:
    """Anthropic API로 모델을 호출하고 응답 텍스트를 반환한다."""
    client = anthropic.Anthropic(api_key=os.environ[model.api_key_env])
    content = [{"type": "text", "text": COMPARISON_PROMPT}, *_to_anthropic_content(items)]
    response = client.messages.create(
        model=model.model_id,
        max_tokens=512,
        messages=[{"role": "user", "content": content}],
    )
    return next((block.text for block in response.content if block.type == "text"), "")


def _call_openai_compatible(model: ModelCandidate, items: list[tuple[str, Path]]) -> str:
    """OpenAI 호환 엔드포인트(Together, Gemini 등)로 모델을 호출하고 응답 텍스트를 반환한다."""
    client = openai.OpenAI(api_key=os.environ[model.api_key_env], base_url=model.base_url)
    content = [{"type": "text", "text": COMPARISON_PROMPT}, *_to_openai_content(items)]
    response = client.chat.completions.create(
        model=model.model_id,
        max_tokens=512,
        messages=[{"role": "user", "content": content}],
    )
    return response.choices[0].message.content or ""


def _build_frame_items(
    representative_frame: Path, candidate_frames: list[Path]
) -> list[tuple[str, Path]]:
    """대표 컷·후보 프레임에 사람이 읽을 라벨을 붙인 (라벨, 경로) 목록을 만든다."""
    items = [("대표 컷", representative_frame)]
    items += [(f"후보 {i}", frame) for i, frame in enumerate(candidate_frames)]
    return items


def _to_anthropic_content(items: list[tuple[str, Path]]) -> list[dict]:
    """(라벨, 경로) 목록을 Anthropic 메시지 콘텐츠 블록 형식으로 변환한다."""
    content = []
    for label, path in items:
        data, media_type = _read_base64(path)
        content.append({"type": "text", "text": f"{label}:"})
        content.append(
            {"type": "image", "source": {"type": "base64", "media_type": media_type, "data": data}}
        )
    return content


def _to_openai_content(items: list[tuple[str, Path]]) -> list[dict]:
    """(라벨, 경로) 목록을 OpenAI 호환 메시지 콘텐츠 블록 형식으로 변환한다."""
    content = []
    for label, path in items:
        data, media_type = _read_base64(path)
        content.append({"type": "text", "text": f"{label}:"})
        content.append(
            {"type": "image_url", "image_url": {"url": f"data:{media_type};base64,{data}"}}
        )
    return content


def _read_base64(path: Path) -> tuple[str, str]:
    """이미지 파일을 base64로 인코딩하고 확장자로 추정한 media type과 함께 반환한다."""
    data = base64.standard_b64encode(path.read_bytes()).decode("utf-8")
    media_type = "image/jpeg" if path.suffix.lower() in (".jpg", ".jpeg") else "image/png"
    return data, media_type


def _looks_like_valid_schema(raw_output: str) -> bool:
    """응답에서 JSON을 추출해 파싱되고 기대 필드를 전부 갖췄는지 확인한다."""
    start, end = raw_output.find("{"), raw_output.rfind("}")
    if start == -1 or end == -1:
        return False
    try:
        parsed = json.loads(raw_output[start : end + 1])
    except json.JSONDecodeError:
        return False
    return {"improved", "selected_frame", "reason"}.issubset(parsed.keys())


def _print_summary(results: list[ComparisonRun]) -> None:
    """모델별 비교 결과를 사람이 읽기 쉬운 요약으로 로그에 출력한다."""
    lines = ["=== MLLM 모델 비교 결과 ==="]
    for r in results:
        status = "호출 실패" if r.error else ("스키마 OK" if r.valid_json else "스키마 불일치")
        lines.append(f"- {r.model_name}: {r.latency_seconds:.2f}s, {status}")
        lines.append(f"  응답: {r.error or r.raw_output}")
    logger.info("\n".join(lines))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="MLLM 후보 모델 비교")
    parser.add_argument("representative", type=Path, help="대표 컷 이미지 경로")
    parser.add_argument("candidates", type=Path, nargs="+", help="후보 프레임 이미지 경로들")
    args = parser.parse_args()

    run_results = run_comparison(args.representative, args.candidates)
    _print_summary(run_results)
