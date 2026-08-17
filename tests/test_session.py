# tests/test_session.py
"""POST /api/session/utterance 엔드포인트를 확인하는 테스트."""

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai.target_spec import TargetSpec, TargetSpecSource, TargetSpecStatus
from backend.api.session import router as session_router

app = FastAPI()
app.include_router(session_router)
client = TestClient(app)


@pytest.fixture(autouse=True)
def _no_llm_fallback(monkeypatch):
    """저신뢰 발화가 LLM 폴백을 타지 않게 막는다 — 이 파일은 엔드포인트 계약만 검증한다."""
    monkeypatch.setattr("ai.llm_fallback.resolve_with_llm", lambda *args, **kwargs: None)


def _post(session_id: str, raw_text: str):
    return client.post(
        "/api/session/utterance",
        json={"session_id": session_id, "raw_text": raw_text},
    )


def test_parses_utterance_into_target_spec():
    """발화에서 뽑아낸 슬롯이 그대로 응답 스펙에 담긴다."""
    response = _post("s1", "두 명이 클로즈업으로 찍어줘")

    assert response.status_code == 200
    body = response.json()
    assert body["sessionId"] == "s1"
    assert body["rawText"] == "두 명이 클로즈업으로 찍어줘"
    assert body["subjectCount"] == 2
    assert body["framing"] == "closeup"
    assert body["status"] == "ok"
    assert body["source"] == "ondevice"


def test_response_is_parseable_by_shared_schema():
    """응답 JSON이 ①·②가 공유하는 TargetSpec 계약으로 그대로 역파싱된다."""
    response = _post("s2", "노트북 찍어줘")

    spec = TargetSpec.from_dict(response.json())
    assert spec.session_id == "s2"
    assert spec.object_label == "laptop"


def test_low_confidence_utterance_still_returns_spec():
    """슬롯이 안 잡혀도 스펙 자체는 돌려준다 — 앱이 조준 단계에서 쓸 수 있어야 한다."""
    response = _post("s3", "사진 찍어줘")

    assert response.status_code == 200
    assert response.json()["status"] == "needs_clarification"


def test_blank_utterance_returns_failed_spec():
    """빈 발화는 4xx가 아니라 status=failed 스펙으로 답한다.

    4xx로 던지면 앱이 일반 촬영 모드로 떨어져 '발화 실패'라는 상태가 사라진다.
    """
    response = _post("s4", "   ")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "failed"
    assert body["sessionId"] == "s4"


def test_parse_failure_returns_failed_spec(monkeypatch):
    """변환이 예외를 던져도 세션을 죽이지 않고 status=failed로 응답한다."""

    def _raise(*args, **kwargs):
        raise RuntimeError("파싱 불가")

    monkeypatch.setattr("backend.api.session.resolve_target_spec", _raise)

    response = _post("s5", "인물 사진 찍어줘")

    assert response.status_code == 200
    assert response.json()["status"] == "failed"


def test_llm_fallback_result_is_returned(monkeypatch):
    """규칙이 실패해 LLM 폴백이 뽑아낸 스펙도 그대로 응답에 담긴다."""
    monkeypatch.setattr(
        "backend.api.session.resolve_target_spec",
        lambda text, session_id: TargetSpec(
            schema_version="0.2",
            session_id=session_id,
            raw_text=text,
            status=TargetSpecStatus.OK,
            subject_count=2,
            confidence=0.8,
            source=TargetSpecSource.ONDEVICE,
        ),
    )

    response = _post("s6", "옆 사람까지 같이 담아줘")

    assert response.status_code == 200
    assert response.json()["subjectCount"] == 2
