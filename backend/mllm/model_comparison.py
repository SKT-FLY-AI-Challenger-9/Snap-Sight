# backend/mllm/model_comparison.py

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

logger = load_logger(__name__)

COMPARISON_PROMPT = (
    "대표 컷과 후보 프레임들을 비교해서, 표정·눈감음·의도 부합성 기준으로 "
    "더 나은 프레임이 있는지 판단하라. 반드시 아래 JSON 형식으로만 답하라:\n"
    '{"improved": bool, "selected_frame": "후보 라벨 또는 null", "reason": "판단 근거"}'
)

@dataclass
class ModelCandidate:
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
]


@dataclass
class ComparisonRun:
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
    models = models if models is not None else MODEL_CANDIDATES
    items = _build_frame_items(representative_frame, candidate_frames)
    return [_run_single_model(model, items) for model in models]


def _run_single_model(model: ModelCandidate, items: list[tuple[str, Path]]) -> ComparisonRun:
    start = time.monotonic()
    try:
        raw_output = (
            _call_anthropic(model, items)
            if model.provider == "anthropic"
            else _call_openai_compatible(model, items)
        )
    except Exception as exc:
        latency = time.monotonic() - start
        logger.error(f"{model.name} 호출 실패: {exc}")
        return ComparisonRun(model.name, latency, "", False, error=str(exc))

    latency = time.monotonic() - start
    return ComparisonRun(model.name, latency, raw_output, _looks_like_valid_schema(raw_output))


def _call_anthropic(model: ModelCandidate, items: list[tuple[str, Path]]) -> str:
    client = anthropic.Anthropic(api_key=os.environ[model.api_key_env])
    content = [{"type": "text", "text": COMPARISON_PROMPT}, *_to_anthropic_content(items)]
    response = client.messages.create(
        model=model.model_id,
        max_tokens=512,
        messages=[{"role": "user", "content": content}],
    )
    return next((block.text for block in response.content if block.type == "text"), "")


def _call_openai_compatible(model: ModelCandidate, items: list[tuple[str, Path]]) -> str:
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
    items = [("대표 컷", representative_frame)]
    items += [(f"후보 {i}", frame) for i, frame in enumerate(candidate_frames)]
    return items


def _to_anthropic_content(items: list[tuple[str, Path]]) -> list[dict]:
    content = []
    for label, path in items:
        data, media_type = _read_base64(path)
        content.append({"type": "text", "text": f"{label}:"})
        content.append(
            {"type": "image", "source": {"type": "base64", "media_type": media_type, "data": data}}
        )
    return content


def _to_openai_content(items: list[tuple[str, Path]]) -> list[dict]:
    content = []
    for label, path in items:
        data, media_type = _read_base64(path)
        content.append({"type": "text", "text": f"{label}:"})
        content.append({"type": "image_url", "image_url": {"url": f"data:{media_type};base64,{data}"}})
    return content


def _read_base64(path: Path) -> tuple[str, str]:
    data = base64.standard_b64encode(path.read_bytes()).decode("utf-8")
    media_type = "image/jpeg" if path.suffix.lower() in (".jpg", ".jpeg") else "image/png"
    return data, media_type


def _looks_like_valid_schema(raw_output: str) -> bool:
    start, end = raw_output.find("{"), raw_output.rfind("}")
    if start == -1 or end == -1:
        return False
    try:
        parsed = json.loads(raw_output[start : end + 1])
    except json.JSONDecodeError:
        return False
    return {"improved", "selected_frame", "reason"}.issubset(parsed.keys())


def _print_summary(results: list[ComparisonRun]) -> None:
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
