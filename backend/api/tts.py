# backend/api/tts.py
"""① 재질문·에러 안내 등에 쓰이는 TTS 프록시 엔드포인트 (ElevenLabs / SKT A.X)."""

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool

from ai.skt_tts_client import (
    SKT_ALLOWED_VOICES,
    SKT_MAX_TEXT_LENGTH,
    SktTtsClient,
    SktTtsError,
)
from ai.tts_client import ElevenLabsTTSClient, TTSError
from backend.api.guards import require_api_access
from backend.utils.logger import load_logger

logger = load_logger("tts.log")

router = APIRouter(dependencies=[Depends(require_api_access)])


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
        audio = await run_in_threadpool(client.synthesize, request.text)
    except RuntimeError as e:
        logger.error(f"TTS 클라이언트 초기화 실패: {e}")
        raise HTTPException(status_code=503, detail="TTS 서비스를 사용할 수 없습니다.") from e
    except TTSError as e:
        logger.warning(f"TTS 요청 실패: {e}")
        raise HTTPException(status_code=502, detail=str(e)) from e

    return Response(content=audio, media_type="audio/mpeg")


class SktTtsRequest(BaseModel):
    """POST /api/tts/skt 요청 스키마 — 앱 보이스 프리셋의 동적 문장 합성."""

    text: str
    voice: str = "aria"


@router.post("/api/tts/skt")
async def synthesize_skt_speech(request: SktTtsRequest) -> Response:
    """동적 문장(촬영 요약·사진 설명)을 앱 프리셋 보이스(SKT A.X)로 합성해 mp3 로 반환한다.

    고정 스크립트 문장은 앱에 프리캐싱돼 있으므로 이 경로로 오지 않는다.
    """
    text = request.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="text는 비어 있을 수 없습니다.")
    if len(text) > SKT_MAX_TEXT_LENGTH:
        # API 제약(300자 미만) — 긴 설명은 낭독 가능한 앞부분만 합성한다
        text = text[:SKT_MAX_TEXT_LENGTH]
    if request.voice not in SKT_ALLOWED_VOICES:
        raise HTTPException(status_code=422, detail="지원하지 않는 voice 입니다.")

    try:
        client = SktTtsClient()
        audio = await run_in_threadpool(client.synthesize, text, request.voice)
    except RuntimeError as e:
        logger.error(f"SKT TTS 클라이언트 초기화 실패: {e}")
        raise HTTPException(status_code=503, detail="TTS 서비스를 사용할 수 없습니다.") from e
    except SktTtsError as e:
        logger.warning(f"SKT TTS 요청 실패: {e}")
        raise HTTPException(status_code=502, detail=str(e)) from e

    return Response(content=audio, media_type="audio/mpeg")
