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


def _make_silent_wav(path: str, duration_sec: float = 1.0) -> None:
    """무음 오디오 실패 케이스 테스트용 WAV 파일을 생성한다."""
    import wave

    with wave.open(path, "w") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(16000)
        wav_file.writeframes(b"\x00\x00" * int(16000 * duration_sec))


if __name__ == "__main__":
    from dotenv import load_dotenv

    load_dotenv()
    client = ClovaSTTClient()

    print("=== 정상 인식 (ai/samples/*.wav 있으면 실행, 본인 목소리로 녹음해서 넣으면 됨) ===")
    for path in [
        "ai/samples/sample_person.wav",
        "ai/samples/sample_object.wav",
        "ai/samples/sample_landscape.wav",
    ]:
        if not os.path.exists(path):
            print(f"{path} 없음, 건너뜀")
            continue
        try:
            print(f"{path} -> {client.transcribe(path)!r}")
        except STTError as e:
            print(f"{path} -> STT 실패: {e}")

    print("\n=== 실패 케이스: 무음 오디오 (STTError 발생이 정상) ===")
    silent_path = "ai/samples/_silent_test.wav"
    _make_silent_wav(silent_path)
    try:
        client.transcribe(silent_path)
        print("예상과 다르게 텍스트가 인식됨")
    except STTError as e:
        print(f"예상대로 STTError 발생: {e}")
    finally:
        os.remove(silent_path)

    print("\n=== 실패 케이스: 잘못된 인증키 (STTError 발생이 정상) ===")
    bad_client = ClovaSTTClient.__new__(ClovaSTTClient)
    bad_client.client_id = client.client_id
    bad_client.client_secret = "invalid-secret-for-testing"
    _make_silent_wav(silent_path, duration_sec=0.1)
    try:
        bad_client.transcribe(silent_path)
        print("예상과 다르게 성공함")
    except STTError as e:
        print(f"예상대로 STTError 발생: {e}")
    finally:
        os.remove(silent_path)
