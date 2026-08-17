# tests/test_tts.py
"""POST /api/tts 엔드포인트를 mock으로 검증하는 테스트 (실제 네트워크 호출 없음)."""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai.tts_client import ElevenLabsTTSClient, TTSError
from backend.api.tts import router as tts_router

app = FastAPI()
app.include_router(tts_router)
client = TestClient(app)


def test_synthesize_speech_returns_audio(monkeypatch):
    monkeypatch.setattr(ElevenLabsTTSClient, "__init__", lambda self: None)
    monkeypatch.setattr(ElevenLabsTTSClient, "synthesize", lambda self, text: b"fake-mp3-bytes")

    response = client.post("/api/tts", json={"text": "안녕하세요"})

    assert response.status_code == 200
    assert response.headers["content-type"] == "audio/mpeg"
    assert response.content == b"fake-mp3-bytes"


def test_synthesize_speech_rejects_empty_text():
    response = client.post("/api/tts", json={"text": "   "})

    assert response.status_code == 400


def test_synthesize_speech_returns_502_on_tts_error(monkeypatch):
    def fake_synthesize(self, text):
        raise TTSError("업스트림 실패")

    monkeypatch.setattr(ElevenLabsTTSClient, "__init__", lambda self: None)
    monkeypatch.setattr(ElevenLabsTTSClient, "synthesize", fake_synthesize)

    response = client.post("/api/tts", json={"text": "안녕"})

    assert response.status_code == 502


def test_synthesize_speech_returns_503_when_client_unconfigured(monkeypatch):
    def fake_init(self):
        raise RuntimeError("ELEVENLABS_API_KEY / ELEVENLABS_VOICE_ID 환경변수가 필요합니다.")

    monkeypatch.setattr(ElevenLabsTTSClient, "__init__", fake_init)

    response = client.post("/api/tts", json={"text": "안녕"})

    assert response.status_code == 503
