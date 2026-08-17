# backend/mllm/target_requirements.py
"""①의 TargetSpec을 MLLM 비교 프롬프트가 기대하는 structured_requirements로 변환한다."""

from ai.llm_fallback import resolve_target_spec
from ai.target_spec import Framing, SubjectType, TargetSpec, TargetSpecStatus
from backend.utils.logger import load_logger

logger = load_logger("mllm_target_requirements.log")

# 프롬프트 본문과 few-shot 예시({"인원수": "2명"})가 한국어라 키·값을 한국어로 맞춘다.
# object_label만 Objects365 canonical label(영문)을 그대로 쓴다 — 한글 역매핑은 다대일이라
# ("배" → ship/pear) 되돌릴 때 모호해지고, 모델은 영문 라벨을 그대로 이해한다.
FRAMING_LABELS = {
    Framing.CLOSEUP: "클로즈업",
    Framing.WIDE: "넓은 화각",
}

LANDSCAPE_SUBJECT = "풍경"


def build_requirements_from_text(session_id: str, raw_text: str) -> dict[str, str]:
    """발화 원문을 TargetSpec으로 파싱해 프롬프트용 요구사항으로 변환한다.

    규칙 기반 파서가 저신뢰로 판정하면 resolve_target_spec이 LLM 폴백까지 태운다 —
    OBJECT_LABEL_KEYWORDS에 없는 표현도 스펙으로 뽑히도록 커버리지를 넓히기 위함이다.

    파싱에 실패해도 예외를 올리지 않는다 — 요구사항 없이도 MLLM은 범용 결함 기준(3단계)으로
    비교할 수 있으므로, 발화 해석 실패가 비교 자체를 막아서는 안 된다.
    """
    if not raw_text.strip():
        return {}

    try:
        spec = resolve_target_spec(raw_text, session_id)
    except (TypeError, ValueError) as exc:
        logger.error(f"세션 {session_id}: 발화 파싱 실패로 요구사항 없이 비교 진행 — {exc}")
        return {}

    requirements = build_structured_requirements(spec)
    logger.info(
        f"세션 {session_id}: 타겟 스펙 파싱 완료 — status={spec.status.value}, "
        f"confidence={spec.confidence}, 요구사항 {len(requirements)}건"
    )
    return requirements


def build_structured_requirements(spec: TargetSpec) -> dict[str, str]:
    """TargetSpec에서 사용자가 명시적으로 말한 슬롯만 골라 요구사항으로 만든다.

    기본값으로 채워진 슬롯은 제외한다 — 프롬프트가 "사용자가 말하지 않은 표정·자세·구도는
    절대로 요구사항으로 가정하지 마십시오"를 명시하므로, 기본값을 넣으면 없는 요구사항을
    만들어내 대표 컷을 잘못 교체하게 된다.

    confidence는 필터로 쓰지 않는다. confidence는 "몇 개의 슬롯이 매칭됐는가"의 함수인데
    이 함수는 이미 매칭된 슬롯만 담으므로, 낮은 confidence가 담긴 값의 신뢰도를 떨어뜨리지
    않는다. status=failed일 때만 발화 자체가 유효하지 않다고 보고 전부 버린다.
    """
    if spec.status is TargetSpecStatus.FAILED:
        return {}

    requirements: dict[str, str] = {}

    subject = _format_subject(spec)
    if subject is not None:
        requirements["피사체"] = subject

    # subject_count는 "N명"·"두 명"처럼 사람을 세는 표현에서만 채워지므로 단위는 항상 '명'이다.
    if spec.subject_count is not None:
        requirements["인원수"] = f"{spec.subject_count}명"

    if spec.framing is not Framing.FULL_BODY:
        requirements["구도"] = FRAMING_LABELS[spec.framing]

    return requirements


def _format_subject(spec: TargetSpec) -> str | None:
    """명시적으로 지목된 피사체만 반환한다.

    subject_type=person은 파서의 기본값이라 "인물 사진 찍어줘"라고 말한 경우와 아무 말도
    하지 않은 경우를 구분할 수 없다. 그래서 person은 요구사항으로 올리지 않는다.
    """
    if spec.object_label is not None:
        return spec.object_label
    if spec.subject_type is SubjectType.LANDSCAPE:
        return LANDSCAPE_SUBJECT
    return None
