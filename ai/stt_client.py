import os
import requests

STT_ENDPOINT = "https://naveropenapi.apigw.ntruss.com/recog/v1/stt"


class STTError(Exception):
    """STT 호출/처리 중 발생하는 예외."""
    pass


class ClovaSTTClient:
    """네이버 클로바 단문 인식 API를 호출해 오디오를 텍스트로 변환하는 클라이언트."""

    def __init__(self):
        self.client_id = os.environ.get("NCP_CLOVA_CLIENT_ID")
        self.client_secret = os.environ.get("NCP_CLOVA_CLIENT_SECRET")
        if not self.client_id or not self.client_secret:
            raise RuntimeError("NCP_CLOVA_CLIENT_ID / NCP_CLOVA_CLIENT_SECRET 환경변수가 필요합니다.")

    def transcribe(self, audio_path: str, lang: str = "Kor") -> str:
        """오디오 파일 경로를 받아 인식된 텍스트를 반환한다. 60초 이내 오디오만 지원."""
        headers = {
            "X-NCP-APIGW-API-KEY-ID": self.client_id,
            "X-NCP-APIGW-API-KEY": self.client_secret,
            "Content-Type": "application/octet-stream",
        }
        with open(audio_path, "rb") as audio_file:
            response = requests.post(
                STT_ENDPOINT,
                headers=headers,
                params={"lang": lang},
                data=audio_file,
                timeout=10,
            )
        if response.status_code != 200:
            raise STTError(f"STT 요청 실패: {response.status_code} {response.text}")
        text = response.json().get("text", "").strip()
        if not text:
            raise STTError("인식된 텍스트가 없습니다.")
        return text


if __name__ == "__main__":
    client = ClovaSTTClient()
    try:
        print("인식 결과:", client.transcribe("ai/samples/sample_command.wav"))
    except STTError as e:
        print("STT 실패:", e)
