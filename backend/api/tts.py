# backend/api/tts.py
"""① 재질문·에러 안내 등에 쓰이는 TTS(ElevenLabs) 프록시 엔드포인트."""

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel

from ai.tts_client import ElevenLabsTTSClient, TTSError
from backend.utils.logger import load_logger

logger = load_logger("tts.log")

router = APIRouter()


class TtsRequest(BaseModel):
    """POST /api/tts 요청 스키마."""

    text: str


@router.post("/api/tts")
async def synthesize_speech(request: TtsRequest) -> Response:
    """텍스트를 mp3 오디오로 변환해 반환한다. Android는 이 응답을 그대로 재생하면 된다."""
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="text는 비어 있을 수 없습니다.")

    try:
        client = ElevenLabsTTSClient()
        audio = client.synthesize(request.text)
    except RuntimeError as e:
        logger.error(f"TTS 클라이언트 초기화 실패: {e}")
        raise HTTPException(status_code=503, detail="TTS 서비스를 사용할 수 없습니다.") from e
    except TTSError as e:
        logger.warning(f"TTS 요청 실패: {e}")
        raise HTTPException(status_code=502, detail=str(e)) from e

    return Response(content=audio, media_type="audio/mpeg")
