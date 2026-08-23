# tests/test_llm_fallback.py
"""ai.llm_fallback의 LLM 보완 로직을 mock으로 검증하는 테스트 (실제 네트워크 호출 없음)."""

import httpx
from anthropic import APIConnectionError, APIStatusError

from ai.llm_fallback import ExtractedIntent, resolve_target_spec, resolve_with_llm
from ai.target_spec import Framing, SubjectType, TargetSpecSource, TargetSpecStatus

DUMMY_REQUEST = httpx.Request("POST", "https://api.anthropic.com/v1/messages")


class _FakeResponse:
    """Anthropic 클라이언트가 반환하는 messages.parse() 응답을 흉내낸다."""

    def __init__(self, parsed_output):
        self.parsed_output = parsed_output


class _FakeMessages:
    def __init__(self, response=None, error=None):
        self._response = response
        self._error = error

    def parse(self, **kwargs):
        if self._error is not None:
            raise self._error
        return self._response


class _FakeAnthropicClient:
    """실제 네트워크 호출 없이 Anthropic() 생성자를 대체하는 가짜 클라이언트."""

    def __init__(self, response=None, error=None):
        self.messages = _FakeMessages(response=response, error=error)


def _patch_anthropic(monkeypatch, *, response=None, error=None):
    monkeypatch.setattr(
        "ai.llm_fallback.Anthropic",
        lambda: _FakeAnthropicClient(response=response, error=error),
    )


def test_resolve_with_llm_returns_target_spec_on_success(monkeypatch):
    extracted = ExtractedIntent(
        subject_type=SubjectType.OBJECT,
        object_label="cup",
        subject_count=None,
        framing=Framing.FULL_BODY,
        confidence=0.9,
    )
    _patch_anthropic(monkeypatch, response=_FakeResponse(extracted))

    spec = resolve_with_llm("저 컵 찍어줘", session_id="sess_1", source=TargetSpecSource.ONDEVICE)

    assert spec is not None
    assert spec.subject_type is SubjectType.OBJECT
    assert spec.object_label == "cup"
    assert spec.status is TargetSpecStatus.OK
    assert spec.raw_text == "저 컵 찍어줘"


def test_resolve_with_llm_returns_none_on_connection_error(monkeypatch):
    _patch_anthropic(monkeypatch, error=APIConnectionError(request=DUMMY_REQUEST))

    assert resolve_with_llm("찍어줘", "sess_2", TargetSpecSource.ONDEVICE) is None


def test_resolve_with_llm_returns_none_on_api_status_error(monkeypatch):
    response = httpx.Response(500, request=DUMMY_REQUEST)
    _patch_anthropic(monkeypatch, error=APIStatusError("서버 오류", response=response, body=None))

    assert resolve_with_llm("찍어줘", "sess_3", TargetSpecSource.ONDEVICE) is None


def test_resolve_with_llm_returns_none_when_response_fails_to_parse(monkeypatch):
    _patch_anthropic(monkeypatch, response=_FakeResponse(None))

    assert resolve_with_llm("찍어줘", "sess_4", TargetSpecSource.ONDEVICE) is None


def test_resolve_with_llm_nulls_object_label_not_in_taxonomy(monkeypatch):
    """프롬프트에 허용 목록을 줬어도 LLM이 없는 값을 지어낼 수 있으니 한 번 더 검증한다."""
    extracted = ExtractedIntent(
        subject_type=SubjectType.OBJECT,
        object_label="not_a_real_label",
        subject_count=None,
        framing=Framing.FULL_BODY,
        confidence=0.9,
    )
    _patch_anthropic(monkeypatch, response=_FakeResponse(extracted))

    spec = resolve_with_llm("찍어줘", "sess_5", TargetSpecSource.ONDEVICE)

    assert spec is not None
    assert spec.object_label is None


def test_resolve_with_llm_nulls_object_label_when_subject_type_is_not_object(monkeypatch):
    extracted = ExtractedIntent(
        subject_type=SubjectType.PERSON,
        object_label="cup",  # 모순된 응답 — subject_type이 person이면 무시돼야 함
        subject_count=None,
        framing=Framing.FULL_BODY,
        confidence=0.9,
    )
    _patch_anthropic(monkeypatch, response=_FakeResponse(extracted))

    spec = resolve_with_llm("찍어줘", "sess_6", TargetSpecSource.ONDEVICE)

    assert spec is not None
    assert spec.object_label is None


def test_resolve_with_llm_returns_none_when_target_spec_validation_fails(monkeypatch):
    """subject_count=0처럼 TargetSpec 자체가 거부하는 값을 LLM이 반환해도 안전하게 폴백한다."""
    extracted = ExtractedIntent(
        subject_type=SubjectType.PERSON,
        object_label=None,
        subject_count=0,
        framing=Framing.FULL_BODY,
        confidence=0.9,
    )
    _patch_anthropic(monkeypatch, response=_FakeResponse(extracted))

    assert resolve_with_llm("찍어줘", "sess_7", TargetSpecSource.ONDEVICE) is None


def test_resolve_target_spec_skips_llm_when_rule_based_is_confident(monkeypatch):
    """규칙 기반이 이미 충분히 확신하면 LLM을 아예 호출하지 않는다."""
    def fail_if_called():
        raise AssertionError("confidence 충분한데 LLM을 호출하면 안 됨")

    monkeypatch.setattr("ai.llm_fallback.Anthropic", lambda: fail_if_called())

    spec = resolve_target_spec("혼자 전신 나오게 찍어줘", "sess_8", TargetSpecSource.ONDEVICE)

    assert spec.status is TargetSpecStatus.OK
    assert spec.subject_count == 1


def test_resolve_target_spec_uses_llm_result_when_confident(monkeypatch):
    extracted = ExtractedIntent(
        subject_type=SubjectType.OBJECT,
        object_label="cup",
        subject_count=None,
        framing=Framing.FULL_BODY,
        confidence=0.95,
    )
    _patch_anthropic(monkeypatch, response=_FakeResponse(extracted))

    # "그냥 사진 찍어줘"는 규칙 기반으로는 신호 0개(NEEDS_CLARIFICATION) — LLM 폴백 대상
    spec = resolve_target_spec("그냥 사진 찍어줘", "sess_9", TargetSpecSource.ONDEVICE)

    assert spec.status is TargetSpecStatus.OK
    assert spec.object_label == "cup"


def test_resolve_target_spec_falls_back_to_rule_based_when_llm_fails(monkeypatch):
    _patch_anthropic(monkeypatch, error=APIConnectionError(request=DUMMY_REQUEST))

    spec = resolve_target_spec("그냥 사진 찍어줘", "sess_10", TargetSpecSource.ONDEVICE)

    # LLM 실패 -> 규칙 기반 결과(NEEDS_CLARIFICATION) 그대로, 세션이 죽지 않음
    assert spec.status is TargetSpecStatus.NEEDS_CLARIFICATION
    assert spec.raw_text == "그냥 사진 찍어줘"


def test_resolve_target_spec_falls_back_when_llm_is_also_unconfident(monkeypatch):
    extracted = ExtractedIntent(
        subject_type=SubjectType.PERSON,
        object_label=None,
        subject_count=None,
        framing=Framing.FULL_BODY,
        confidence=0.3,  # LLM도 확신 없음
    )
    _patch_anthropic(monkeypatch, response=_FakeResponse(extracted))

    spec = resolve_target_spec("그냥 사진 찍어줘", "sess_11", TargetSpecSource.ONDEVICE)

    assert spec.status is TargetSpecStatus.NEEDS_CLARIFICATION
