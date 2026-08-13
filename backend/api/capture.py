# backend/api/capture.py
"""Android(⑤)가 촬영 시점에 로컬 버퍼에서 전송하는 대표 컷·후보 프레임을 받아 저장하는 API 라우터."""

from fastapi import APIRouter, File, Form, UploadFile
from pydantic import BaseModel

from backend.storage.frame_buffer import save_candidate_frame, save_representative_frame
from backend.utils.logger import load_logger

logger = load_logger(__name__)

router = APIRouter()


class CaptureFramesResponse(BaseModel):
    """POST /api/capture/frames 응답 스키마."""

    session_id: str
    received_candidate_count: int
    status: str


@router.post("/api/capture/frames", response_model=CaptureFramesResponse)
async def receive_capture_frames(
    session_id: str = Form(...),
    representative_frame: UploadFile = File(...),
    candidate_frames: list[UploadFile] = File(...),
) -> CaptureFramesResponse:
    """대표 컷 1장과 후보 프레임 목록을 받아 세션 디렉터리에 저장하고 저장 결과를 반환한다."""
    representative_bytes = await representative_frame.read()
    save_representative_frame(session_id, representative_frame.filename, representative_bytes)

    for index, candidate in enumerate(candidate_frames):
        candidate_bytes = await candidate.read()
        save_candidate_frame(session_id, index, candidate.filename, candidate_bytes)

    logger.info(f"세션 {session_id}: 대표 컷 1장, 후보 프레임 {len(candidate_frames)}장 저장 완료")

    return CaptureFramesResponse(
        session_id=session_id,
        received_candidate_count=len(candidate_frames),
        status="saved",
    )
