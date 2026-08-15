# backend/api/capture.py
"""Android(⑤)가 촬영 시점에 로컬 버퍼에서 전송하는 대표 컷·후보 프레임을 받아 저장하고,
저장 완료 후 MLLM 후보 비교를 비동기로 트리거하는 API 라우터."""

import json

from fastapi import APIRouter, BackgroundTasks, File, Form, HTTPException, UploadFile
from pydantic import BaseModel

from backend.mllm.orchestration import trigger_comparison
from backend.storage.comparison_result import load_comparison_result
from backend.storage.frame_buffer import save_candidate_frame, save_representative_frame
from backend.utils.logger import load_logger

logger = load_logger("capture.log")

router = APIRouter()


class CaptureFramesResponse(BaseModel):
    """POST /api/capture/frames 응답 스키마."""

    session_id: str
    received_candidate_count: int
    status: str


class CaptureResultResponse(BaseModel):
    """GET /api/capture/{session_id}/result 응답 스키마."""

    status: str
    improved: bool | None
    reason: str | None


@router.post("/api/capture/frames", response_model=CaptureFramesResponse)
async def receive_capture_frames(
    background_tasks: BackgroundTasks,
    session_id: str = Form(...),
    raw_text: str = Form(...),
    representative_frame: UploadFile = File(...),
    candidate_frames: list[UploadFile] = File(default_factory=list),
    candidate_scores: str = Form(default="[]"),
) -> CaptureFramesResponse:
    """대표 컷 1장과 후보 프레임 목록을 저장하고, 후보가 있으면 MLLM 비교를 비동기로 트리거한다."""
    representative_bytes = await representative_frame.read()
    save_representative_frame(session_id, representative_frame.filename, representative_bytes)

    for index, candidate in enumerate(candidate_frames):
        candidate_bytes = await candidate.read()
        save_candidate_frame(session_id, index, candidate.filename, candidate_bytes)

    logger.info(f"세션 {session_id}: 대표 컷 1장, 후보 프레임 {len(candidate_frames)}장 저장 완료")

    if candidate_frames:
        scores = _parse_candidate_scores(candidate_scores)
        background_tasks.add_task(trigger_comparison, session_id, raw_text, scores)
    else:
        logger.info(f"세션 {session_id}: 후보 프레임 없음 — MLLM 비교 스킵")

    return CaptureFramesResponse(
        session_id=session_id,
        received_candidate_count=len(candidate_frames),
        status="saved",
    )


@router.get("/api/capture/{session_id}/result", response_model=CaptureResultResponse)
async def get_capture_result(session_id: str) -> CaptureResultResponse:
    """세션의 MLLM 비교 결과를 조회한다. 아직 안 끝났으면 status=pending을 반환한다."""
    result = load_comparison_result(session_id)
    if result is None:
        return CaptureResultResponse(status="pending", improved=None, reason=None)
    return CaptureResultResponse(status="done", improved=result.improved, reason=result.reason)


def _parse_candidate_scores(candidate_scores: str) -> list[dict]:
    """JSON 문자열로 전달된 온디바이스 점수를 파싱한다. 형식이 잘못되면 명확한 422로 실패한다."""
    try:
        parsed = json.loads(candidate_scores)
    except json.JSONDecodeError as exc:
        raise HTTPException(
            status_code=422, detail=f"candidate_scores가 올바른 JSON이 아닙니다: {exc}"
        ) from exc
    if not isinstance(parsed, list):
        raise HTTPException(status_code=422, detail="candidate_scores는 JSON 배열이어야 합니다")
    return parsed
