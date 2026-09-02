"""개발 중 프롬프트를 실제 이미지에 대해 수동으로 검증하기 위한 CLI 스크립트.

주의: 프로덕션 클라이언트가 아니다. 재시도·프레임 버퍼 연동 없음 (별도 이슈/담당자 몫).
저장소 루트에서 모듈로 실행한다 (pyproject의 pythonpath="." 기준, 다른 backend 모듈과 동일):
    python -m backend.mllm.validate_prompt \
        --raw-text "인물 사진 찍어줘" \
        --requirement 인원수=2명 --requirement 구도=클로즈업 \
        --representative path/to/rep.jpg \
        --candidate path/to/cand1.jpg --candidate path/to/cand2.jpg
"""

from __future__ import annotations

import argparse
import base64
import mimetypes
from pathlib import Path

from anthropic import Anthropic
from dotenv import load_dotenv

from backend.mllm.prompts import SYSTEM_PROMPT, FrameComparisonResult, build_comparison_prompt

MODEL = "claude-sonnet-5"


def _encode_image(path: Path) -> dict:
    media_type = mimetypes.guess_type(path.name)[0] or "image/jpeg"
    data = base64.standard_b64encode(path.read_bytes()).decode("utf-8")
    return {
        "type": "image",
        "source": {"type": "base64", "media_type": media_type, "data": data},
    }


def _parse_requirement(raw: str) -> tuple[str, str]:
    key, sep, value = raw.partition("=")
    if not sep:
        raise argparse.ArgumentTypeError(f"--requirement는 key=value 형식이어야 합니다: {raw!r}")
    return key, value


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-text", required=True)
    parser.add_argument("--requirement", action="append", default=[], metavar="key=value")
    parser.add_argument("--representative", required=True, type=Path)
    parser.add_argument("--candidate", action="append", required=True, type=Path)
    return parser.parse_args()


def main() -> None:
    load_dotenv()
    args = parse_args()

    structured_requirements = dict(_parse_requirement(r) for r in args.requirement)
    prompt_text = build_comparison_prompt(args.raw_text, structured_requirements)

    content = [{"type": "text", "text": prompt_text}]
    content.append({"type": "text", "text": "[대표 컷 / representative]"})
    content.append(_encode_image(args.representative))
    for i, candidate_path in enumerate(args.candidate, start=1):
        content.append({"type": "text", "text": f"[후보 / candidate_{i}]"})
        content.append(_encode_image(candidate_path))

    client = Anthropic()
    response = client.messages.parse(
        model=MODEL,
        max_tokens=1024,
        system=SYSTEM_PROMPT,
        messages=[{"role": "user", "content": content}],
        output_format=FrameComparisonResult,
    )

    result = response.parsed_output
    if result is None:
        print("파싱 실패: 모델 응답이 스키마와 일치하지 않습니다.")
        return

    print(result.model_dump_json(indent=2))


if __name__ == "__main__":
    main()
