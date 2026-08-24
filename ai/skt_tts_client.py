"""SKT A.X TTS(axtts-2-6) 클라이언트 — 동적 문장(촬영 요약·사진 설명)의 즉석 합성용.

고정 스크립트 문장은 앱 assets 에 프리캐싱돼 있고, 이 클라이언트는 카탈로그에 없는
동적 문장을 프리셋 보이스(aria/oliver)로 합성할 때만 쓰인다 (backend/api/tts.py 프록시).
공식 문서의 apis.openapi.sk.com 게이트웨이는 이 appKey 를 거부한다 — tvoice 호스트가 정본
(2026-08-24 실측).
"""

import os

import requests
from dotenv import load_dotenv

load_dotenv()

SKT_TTS_URL = "https://tvoice-api.sktnugu.com/tvoice/openapi/v3/tts"
SKT_TTS_MODEL = "axtts-2-6"
# 앱 보이스 프리셋과 1:1 — 다른 보이스를 열려면 앱 VoicePreset 과 함께 확장한다
SKT_ALLOWED_VOICES = ("aria", "oliver")
# API 제약 (문서 기준): 300자 미만
SKT_MAX_TEXT_LENGTH = 299


class SktTtsError(Exception):
    """SKT TTS 호출/처리 중 발생하는 예외."""


class SktTtsClient:
    """SKT A.X TTS API 를 호출해 텍스트를 mp3 로 합성하는 클라이언트."""

    def __init__(self):
        self.app_key = os.environ.get("SKT_TTS_APP_KEY")
        if not self.app_key:
            raise RuntimeError("SKT_TTS_APP_KEY 환경변수가 필요합니다.")

    def synthesize(self, text: str, voice: str) -> bytes:
        """텍스트를 mp3 바이너리로 합성한다. 실패 시 [SktTtsError]."""
        try:
            response = requests.post(
                SKT_TTS_URL,
                headers={
                    "appKey": self.app_key,
                    "accept": "application/json",
                    "content-type": "application/json",
                },
                json={
                    "model": SKT_TTS_MODEL,
                    "voice": voice,
                    "text": text,
                    "speed": "1",
                    "sr": 22050,
                    "sformat": "mp3",
                    "clientId": "snap-sight",
                },
                timeout=10,
            )
        except requests.exceptions.RequestException as exc:
            raise SktTtsError(f"SKT TTS 요청 실패(네트워크): {exc}") from exc
        if response.status_code != 200:
            raise SktTtsError(f"SKT TTS 요청 실패: {response.status_code} {response.text[:200]}")
        # mp3 매직바이트 검증 — 게이트웨이가 HTML 오류 페이지를 200으로 줄 가능성 방어
        if response.content[:3] not in (b"ID3", b"\xff\xfb", b"\xff\xf3", b"\xff\xf2"):
            raise SktTtsError("SKT TTS 응답이 mp3 가 아님")
        return response.content
