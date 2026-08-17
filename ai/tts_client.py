import os

import requests
from dotenv import load_dotenv

load_dotenv()

TTS_ENDPOINT_TEMPLATE = "https://api.elevenlabs.io/v1/text-to-speech/{voice_id}"


class TTSError(Exception):
    """TTS 호출/처리 중 발생하는 예외."""
    pass


class ElevenLabsTTSClient:
    """일레븐랩스 TTS API를 호출해 텍스트를 음성으로 변환하는 클라이언트."""

    def __init__(self):
        self.api_key = os.environ.get("ELEVENLABS_API_KEY")
        self.voice_id = os.environ.get("ELEVENLABS_VOICE_ID")
        if not self.api_key or not self.voice_id:
            raise RuntimeError("ELEVENLABS_API_KEY / ELEVENLABS_VOICE_ID 환경변수가 필요합니다.")

    def synthesize(self, text: str) -> bytes:
        """텍스트를 받아 mp3 오디오 바이너리를 반환한다."""
        headers = {
            "xi-api-key": self.api_key,
            "Content-Type": "application/json",
        }
        body = {
            "text": text,
            "model_id": "eleven_flash_v2_5",
        }
        try:
            response = requests.post(
                TTS_ENDPOINT_TEMPLATE.format(voice_id=self.voice_id),
                headers=headers,
                json=body,
                timeout=10,
            )
        except requests.exceptions.RequestException as exc:
            raise TTSError(f"TTS 요청 실패(네트워크): {exc}") from exc
        if response.status_code != 200:
            raise TTSError(f"TTS 요청 실패: {response.status_code} {response.text}")
        return response.content


if __name__ == "__main__":
    client = ElevenLabsTTSClient()
    try:
        audio = client.synthesize("촬영이 완료됐어요")
        with open("ai/samples/tts_output.mp3", "wb") as f:
            f.write(audio)
        print("TTS 저장 완료: ai/samples/tts_output.mp3")
    except TTSError as e:
        print("TTS 실패:", e)
