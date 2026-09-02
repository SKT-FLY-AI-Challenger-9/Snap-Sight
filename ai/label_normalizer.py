"""ai/label_normalizer.py

사용자 커스텀 라벨 등록 시 기존 커스텀 라벨과의 동의어 병합 판정 (기능 3 확장,
실사용 피드백 2026-08-22).

"내 개"를 등록해 둔 사용자가 나중에 "내 강아지"를 등록하면 같은 대상을 가리키는 라벨이
두 개 생겨 검색이 갈라진다. 등록 시점에 저비용 LLM으로 기존 라벨 중 같은 뜻이 있는지
판정해, 있으면 기존 라벨로 병합한다.

ai/llm_fallback.py와 같은 패턴(Anthropic().messages.parse(output_format=...), 호출/검증
실패 시 안전한 폴백)을 따른다. 실패 시 폴백은 항상 "새 라벨"이다 — 잘못 합치는 것보다
라벨이 하나 더 생기는 쪽이 복구 가능한 실수이기 때문이다.
"""

from __future__ import annotations

import logging
from typing import Literal

from anthropic import Anthropic, APIConnectionError, APIStatusError
from dotenv import load_dotenv
from pydantic import BaseModel

load_dotenv()

logger = logging.getLogger(__name__)

# 짧은 텍스트 동의어 판정이라 저비용 모델로 충분하다 (ai/llm_fallback.py와 동일 모델).
MODEL_ID = "claude-haiku-4-5-20251001"
MAX_TOKENS = 128

SYSTEM_PROMPT = """당신은 시각장애인용 사진 앱의 라벨 사전 관리자입니다.

사용자가 사진에 붙이려는 새 라벨이, 이미 등록된 라벨 목록 중 하나와 **같은 대상**을
가리키는 다른 표현인지 판정하십시오.

- 같은 대상을 다르게 부른 것(예: "내 개"와 "내 강아지", "우리 아들"과 "울 아들")이면
  action="merge", canonical_label에 기존 목록의 라벨을 **그대로** 적으십시오.
- 비슷해 보여도 다른 대상일 수 있으면(예: "내 강아지"와 "친구네 강아지") action="new".
- 확신이 없으면 action="new" — 잘못 합치면 사용자의 사진이 엉뚱한 이름으로 묶입니다."""

_USER_PROMPT_TEMPLATE = """새 라벨: "{label}"

기존 라벨 목록:
{existing}

새 라벨이 기존 라벨 중 하나와 같은 대상을 가리키는지 판정하십시오."""


class LabelDecision(BaseModel):
    """새 라벨 처리 판정. merge면 canonical_label은 기존 목록에 있는 라벨 하나다."""

    action: Literal["merge", "new"]
    canonical_label: str | None = None


def normalize_custom_label(label: str, existing_labels: list[str]) -> LabelDecision:
    """새 커스텀 라벨을 기존 라벨과 비교해 병합 여부를 판정한다.

    표기까지 동일한 라벨(공백·대소문자 차이만)은 LLM 없이 즉시 병합하고,
    기존 라벨이 없으면 호출 없이 새 라벨로 처리한다.
    """
    trimmed = label.strip()
    if not trimmed or not existing_labels:
        return LabelDecision(action="new")

    for existing in existing_labels:
        if _norm(existing) == _norm(trimmed):
            return LabelDecision(action="merge", canonical_label=existing)

    try:
        response = Anthropic().messages.parse(
            model=MODEL_ID,
            max_tokens=MAX_TOKENS,
            system=SYSTEM_PROMPT,
            messages=[
                {
                    "role": "user",
                    "content": _USER_PROMPT_TEMPLATE.format(
                        label=trimmed,
                        existing="\n".join(f"- {name}" for name in existing_labels),
                    ),
                }
            ],
            output_format=LabelDecision,
        )
    except (APIConnectionError, APIStatusError) as exc:
        logger.warning(f"라벨 병합 판정 LLM 호출 실패 — 새 라벨로 처리: {exc}")
        return LabelDecision(action="new")

    decision = response.parsed_output
    if decision is None:
        logger.warning("라벨 병합 판정 응답 파싱 실패 — 새 라벨로 처리")
        return LabelDecision(action="new")
    if decision.action == "merge" and decision.canonical_label not in existing_labels:
        # LLM이 목록 밖 라벨을 지어냈다 — 병합 근거가 없으므로 새 라벨로 처리
        logger.warning(f"병합 대상이 기존 목록에 없음({decision.canonical_label!r}) — 새 라벨로 처리")
        return LabelDecision(action="new")
    return decision


def _norm(text: str) -> str:
    return text.strip().lower().replace(" ", "")
