"""ai/llm_fallback.py

규칙 기반 슬롯 파서(ai/slot_parser.py)가 저신뢰도(status=NEEDS_CLARIFICATION)로 판정한
발화를 Claude로 다시 추출한다 (STT-5, 이슈 #34).

트리거 조건("언제 LLM을 부를지")은 이 모듈이 아니라 [resolve_target_spec]이 결정한다 —
[resolve_with_llm] 자체는 "주어진 텍스트에서 타겟 스펙 필드를 뽑아내는 것"만 책임진다.

backend/mllm/client.py와 동일한 패턴(Anthropic().messages.parse(output_format=...),
호출/검증 실패 시 안전한 폴백)을 따른다. 다만 ai/ 레이어는 backend/에 의존하지 않는다는
기존 관례를 지키기 위해 backend.utils.logger 대신 표준 logging을 쓴다.
"""

from __future__ import annotations

import logging

from anthropic import Anthropic, APIConnectionError, APIStatusError
from dotenv import load_dotenv
from pydantic import BaseModel

from ai.slot_parser import parse_target_spec
from ai.target_spec import Framing, SubjectType, TargetSpec, TargetSpecSource, TargetSpecStatus
from ai.taxonomy import OBJECTS365_YOLO26

load_dotenv()

logger = logging.getLogger(__name__)

# 슬롯 필링은 이미지 비교(backend/mllm)보다 훨씬 단순한 작업이라 저비용 모델로 충분하다.
MODEL_ID = "claude-haiku-4-5-20251001"
MAX_TOKENS = 256

# confidence < 이 값이면 재질문 대상(ai/slot_parser.py의 CONFIDENCE_THRESHOLD와 동일 기준).
CONFIDENCE_THRESHOLD = 0.6

SYSTEM_PROMPT = """당신은 카메라 촬영 의도 발화에서 구조화된 정보를 추출하는 보조자입니다.

사용자가 한국어로 짧게 말한 촬영 요청에서 아래 필드를 추출하십시오.

- subject_type: "person"(인물) / "object"(사물) / "landscape"(풍경) 중 하나
- object_label: subject_type이 "object"일 때만 사용. 아래 허용 목록 중 정확히 일치하는
  영문 표기 하나, 또는 목록에 없거나 확신할 수 없으면 반드시 null. 목록에 없는 값을
  지어내지 마십시오.
- subject_count: 언급된 인원/개체 수(본인 제외). 언급 없으면 null.
- framing: "closeup"(클로즈업) / "full_body"(전신, 기본값) / "wide"(풍경·배경 위주)
- confidence: 이 추출 결과에 대한 본인의 확신도 (0.0~1.0)

언급되지 않은 정보를 추측해서 채우지 마십시오 — 불확실하면 각 필드의 기본값(null 또는
full_body)을 그대로 두고 confidence를 낮게 보고하십시오.

## object_label 허용 목록
{object_labels}"""

_USER_PROMPT_TEMPLATE = '발화: "{text}"\n위 발화에서 필드를 추출하십시오.'


class ExtractedIntent(BaseModel):
    """Claude가 발화에서 추출한 타겟 스펙 후보. ai.target_spec.TargetSpec 필드와 대응한다."""

    subject_type: SubjectType
    object_label: str | None
    subject_count: int | None
    framing: Framing
    confidence: float


def resolve_target_spec(
    text: str,
    session_id: str,
    source: TargetSpecSource = TargetSpecSource.ONDEVICE,
) -> TargetSpec:
    """규칙 기반으로 먼저 파싱하고, confidence가 낮으면 LLM 폴백을 시도한다.

    LLM 호출·검증에 실패하거나 LLM도 확신 없는 결과를 내면 규칙 기반 결과를 그대로
    반환한다 — 세션은 어떤 경우에도 죽지 않는다.
    """
    rule_based = parse_target_spec(text, session_id, source)
    if rule_based.status is not TargetSpecStatus.NEEDS_CLARIFICATION:
        return rule_based

    llm_result = resolve_with_llm(text, session_id, source)
    if llm_result is None or llm_result.status is TargetSpecStatus.NEEDS_CLARIFICATION:
        return rule_based
    return llm_result


def resolve_with_llm(
    text: str,
    session_id: str,
    source: TargetSpecSource,
) -> TargetSpec | None:
    """LLM으로 타겟 스펙을 재추출한다. 호출·검증 실패 시 None을 반환한다 (호출부가 규칙
    기반 결과로 폴백해야 함)."""
    client = Anthropic()
    system_prompt = SYSTEM_PROMPT.format(
        object_labels=", ".join(OBJECTS365_YOLO26.object_labels)
    )

    try:
        response = client.messages.parse(
            model=MODEL_ID,
            max_tokens=MAX_TOKENS,
            system=system_prompt,
            messages=[{"role": "user", "content": _USER_PROMPT_TEMPLATE.format(text=text)}],
            output_format=ExtractedIntent,
        )
    except (APIConnectionError, APIStatusError) as exc:
        logger.warning(f"LLM 폴백 호출 실패, 규칙 기반 결과로 유지: {exc}")
        return None

    extracted = response.parsed_output
    if extracted is None:
        logger.warning("LLM 폴백 응답 파싱 실패 — parsed_output이 비어 있음")
        return None

    object_label = _validated_object_label(extracted)

    try:
        return TargetSpec(
            schema_version="0.2",
            session_id=session_id,
            status=(
                TargetSpecStatus.OK
                if extracted.confidence >= CONFIDENCE_THRESHOLD
                else TargetSpecStatus.NEEDS_CLARIFICATION
            ),
            subject_type=extracted.subject_type,
            object_label=object_label,
            subject_count=extracted.subject_count,
            framing=extracted.framing,
            raw_text=text,
            confidence=extracted.confidence,
            source=source,
        )
    except (TypeError, ValueError) as exc:
        logger.warning(f"LLM 결과가 TargetSpec 검증을 통과하지 못함: {exc}")
        return None


def _validated_object_label(extracted: ExtractedIntent) -> str | None:
    """subjectType이 object가 아니거나 taxonomy에 없는 라벨이면 null로 안전하게 폴백한다.

    프롬프트에 허용 목록을 명시했지만, LLM이 그래도 없는 값을 지어낼 가능성을 대비해
    한 번 더 검증한다 — ai/slot_parser.py, ai/target_spec.py와 동일한 방어선.
    """
    if extracted.subject_type is not SubjectType.OBJECT:
        return None
    if extracted.object_label is None:
        return None
    if not OBJECTS365_YOLO26.is_object_label(extracted.object_label):
        logger.warning(f"LLM이 taxonomy에 없는 objectLabel을 반환해 버림: {extracted.object_label!r}")
        return None
    return extracted.object_label
