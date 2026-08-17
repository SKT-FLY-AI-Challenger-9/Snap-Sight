# tests/test_tts_client.py
"""ai.tts_client.ElevenLabsTTSClient을 mock으로 검증하는 테스트 (실제 네트워크 호출 없음)."""

import pytest
import requests

from ai.tts_client import ElevenLabsTTSClient, TTSError


class _FakeResponse:
    def __init__(self, status_code, content=b"", text=""):
        self.status_code = status_code
        self.content = content
        self.text = text


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("ELEVENLABS_API_KEY", "test-key")
    monkeypatch.setenv("ELEVENLABS_VOICE_ID", "test-voice")
    return ElevenLabsTTSClient()


def test_missing_env_vars_raise_runtime_error(monkeypatch):
    monkeypatch.delenv("ELEVENLABS_API_KEY", raising=False)
    monkeypatch.delenv("ELEVENLABS_VOICE_ID", raising=False)

    with pytest.raises(RuntimeError):
        ElevenLabsTTSClient()


def test_synthesize_returns_audio_bytes(client, monkeypatch):
    def fake_post(url, headers, json, timeout):
        assert "test-voice" in url
        assert headers["xi-api-key"] == "test-key"
        assert json["model_id"] == "eleven_flash_v2_5"  # 저지연 모델(0.3초대) 사용 확인
        return _FakeResponse(200, content=b"audio-bytes")

    monkeypatch.setattr("ai.tts_client.requests.post", fake_post)

    assert client.synthesize("안녕하세요") == b"audio-bytes"


def test_synthesize_raises_tts_error_on_non_200(client, monkeypatch):
    monkeypatch.setattr(
        "ai.tts_client.requests.post",
        lambda *a, **kw: _FakeResponse(401, text="unauthorized"),
    )

    with pytest.raises(TTSError):
        client.synthesize("안녕하세요")


def test_synthesize_wraps_network_errors_as_tts_error(client, monkeypatch):
    """타임아웃/연결 실패 같은 네트워크 레벨 예외도 TTSError로 변환되어야
    엔드포인트가 502로 안전하게 처리할 수 있다 (실제로 겪었던 타임아웃 케이스)."""

    def fake_post(*a, **kw):
        raise requests.exceptions.Timeout("connection timed out")

    monkeypatch.setattr("ai.tts_client.requests.post", fake_post)

    with pytest.raises(TTSError):
        client.synthesize("안녕하세요")
