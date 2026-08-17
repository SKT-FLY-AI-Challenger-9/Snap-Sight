# tests/test_target_requirements.py
"""TargetSpec → structured_requirements 변환 규칙을 확인하는 테스트."""

import pytest

from ai.target_spec import Framing, SubjectType, TargetSpec, TargetSpecSource, TargetSpecStatus
from backend.mllm.target_requirements import (
    build_requirements_from_text,
    build_structured_requirements,
)


@pytest.fixture
def no_llm_fallback(monkeypatch):
    """LLM 폴백을 끈다 — 규칙 기반 결과만 검증하는 테스트가 네트워크를 타지 않게 한다."""
    monkeypatch.setattr("ai.llm_fallback.resolve_with_llm", lambda *args, **kwargs: None)


def _spec(**overrides) -> TargetSpec:
    """테스트용 TargetSpec을 만든다. 지정하지 않은 필드는 파서 기본값과 같다."""
    defaults = {
        "schema_version": "0.2",
        "session_id": "sess",
        "raw_text": "사진 찍어줘",
        "source": TargetSpecSource.ONDEVICE,
    }
    return TargetSpec(**{**defaults, **overrides})


def test_explicit_count_and_framing_become_requirements():
    """명시적으로 말한 인원수·구도는 요구사항으로 올라간다."""
    requirements = build_structured_requirements(_spec(subject_count=2, framing=Framing.CLOSEUP))

    assert requirements == {"인원수": "2명", "구도": "클로즈업"}


def test_default_slots_are_excluded():
    """기본값으로만 채워진 스펙은 요구사항을 만들지 않는다."""
    assert build_structured_requirements(_spec()) == {}


def test_full_body_framing_is_excluded_as_default():
    """framing=full_body는 파서 기본값과 구분할 수 없으므로 요구사항에서 뺀다."""
    assert build_structured_requirements(_spec(framing=Framing.FULL_BODY)) == {}


def test_person_subject_type_is_excluded_as_default():
    """subject_type=person도 기본값이라 피사체 요구사항으로 올리지 않는다."""
    assert build_structured_requirements(_spec(subject_type=SubjectType.PERSON)) == {}


def test_object_label_becomes_subject_requirement():
    """object_label은 Objects365 canonical label(영문) 그대로 피사체로 넣는다."""
    requirements = build_structured_requirements(
        _spec(subject_type=SubjectType.OBJECT, object_label="laptop")
    )

    assert requirements == {"피사체": "laptop"}


def test_landscape_subject_type_becomes_subject_requirement():
    """subject_type=landscape는 기본값이 아니므로 피사체 요구사항으로 올린다."""
    requirements = build_structured_requirements(
        _spec(subject_type=SubjectType.LANDSCAPE, framing=Framing.WIDE)
    )

    assert requirements == {"피사체": "풍경", "구도": "넓은 화각"}


def test_needs_clarification_still_keeps_matched_slots():
    """confidence가 낮아도 이미 매칭된 슬롯 자체는 사용자가 말한 내용이므로 유지한다."""
    requirements = build_structured_requirements(
        _spec(status=TargetSpecStatus.NEEDS_CLARIFICATION, subject_count=2, confidence=0.4)
    )

    assert requirements == {"인원수": "2명"}


def test_failed_status_drops_all_requirements():
    """status=failed면 발화 해석 자체가 무효이므로 요구사항을 만들지 않는다."""
    requirements = build_structured_requirements(
        _spec(status=TargetSpecStatus.FAILED, subject_count=2, framing=Framing.CLOSEUP)
    )

    assert requirements == {}


@pytest.mark.parametrize(
    ("raw_text", "expected"),
    [
        ("두 명이 클로즈업으로 찍어줘", {"인원수": "2명", "구도": "클로즈업"}),
        ("노트북 클로즈업으로 찍어줘", {"피사체": "laptop", "구도": "클로즈업"}),
        ("풍경 찍어줘", {"피사체": "풍경", "구도": "넓은 화각"}),
        ("사진 찍어줘", {}),
        # 알려진 한계: "전신"은 FULL_BODY로 파싱되는데 그게 기본값이라 명시 여부를 구분할 수
        # 없다. 요구사항을 빠뜨리는 쪽(보수적)이라 대표 컷을 잘못 교체하지는 않는다.
        ("전신으로 찍어줘", {}),
    ],
)
def test_build_requirements_from_text_end_to_end(raw_text, expected, no_llm_fallback):
    """실제 발화를 규칙 기반 파서에 통과시킨 결과가 기대한 요구사항과 일치한다."""
    assert build_requirements_from_text("sess", raw_text) == expected


def test_blank_text_returns_no_requirements():
    """발화가 비어 있으면 파싱을 시도하지 않고 빈 요구사항을 반환한다."""
    assert build_requirements_from_text("sess", "   ") == {}


def test_parse_failure_returns_no_requirements(monkeypatch):
    """파싱이 예외를 던져도 비교를 막지 않고 빈 요구사항으로 진행한다."""

    def _raise(*args, **kwargs):
        raise ValueError("파싱 불가")

    monkeypatch.setattr("backend.mllm.target_requirements.resolve_target_spec", _raise)

    assert build_requirements_from_text("sess", "인물 사진 찍어줘") == {}


def test_llm_fallback_result_becomes_requirements(monkeypatch):
    """규칙 기반이 저신뢰라 LLM 폴백이 뽑아낸 스펙도 그대로 요구사항이 된다."""
    monkeypatch.setattr(
        "backend.mllm.target_requirements.resolve_target_spec",
        lambda text, session_id: _spec(
            raw_text=text,
            subject_type=SubjectType.OBJECT,
            object_label="laptop",
            framing=Framing.CLOSEUP,
            confidence=0.8,
        ),
    )

    requirements = build_requirements_from_text("sess", "책상 위에 있는 거 크게 찍어줘")

    assert requirements == {"피사체": "laptop", "구도": "클로즈업"}
