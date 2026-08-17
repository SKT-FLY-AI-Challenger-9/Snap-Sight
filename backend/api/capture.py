# backend/api/capture.py
"""Android(⑤)가 촬영 시점에 로컬 버퍼에서 전송하는 대표 컷·후보 프레임을 받아 저장하고,
저장 완료 후 MLLM 후보 비교를 비동기로 트리거하는 API 라우터."""

import json

from fastapi import APIRouter, BackgroundTasks, File, Form, HTTPException, UploadFile
from pydantic import BaseModel

from backend.config import RESULT_POLL_INTERVAL_SECONDS
from backend.mllm.orchestration import trigger_comparison
from backend.storage.comparison_result import load_comparison_result
from backend.storage.frame_buffer import (
    save_candidate_frame,
    save_representative_frame,
    session_exists,
)
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
    # pending일 때만 값이 있다. 앱이 재조회 간격을 하드코딩하지 않도록 서버가 알려준다.
    retry_after_seconds: int | None = None


@router.post("/api/capture/frames", response_model=CaptureFramesResponse)
async def receive_capture_frames(
    background_tasks: BackgroundTasks,
    session_id: str = Form(...),
    # 발화 없는 세션(마이크 미허용·인식 실패)은 빈 문자열이 정상 케이스다.
    # FastAPI(python-multipart)는 빈 폼 값을 "누락"으로 처리하므로 필수로 두면
    # 해당 세션의 업로드가 전부 422 로 거부된다 — 기본값으로 완화한다.
    raw_text: str = Form(default=""),
    representative_frame: UploadFile = File(...),
    candidate_frames: list[UploadFile] = File(default_factory=list),
    candidate_scores: str = Form(default="[]"),
) -> CaptureFramesResponse:
    """대표 컷 1장과 후보 프레임 목록을 저장하고, 후보가 있으면 MLLM 비교를 비동기로 트리거한다."""
    # 파일을 디스크에 쓰기 전에 검증한다 — 저장 후 422로 실패하면 재시도 때 잔여 파일이 남는다.
    scores = _parse_candidate_scores(candidate_scores, len(candidate_frames))

    representative_bytes = await representative_frame.read()
    save_representative_frame(session_id, representative_frame.filename, representative_bytes)

    for index, candidate in enumerate(candidate_frames):
        candidate_bytes = await candidate.read()
        save_candidate_frame(session_id, index, candidate.filename, candidate_bytes)

    logger.info(f"세션 {session_id}: 대표 컷 1장, 후보 프레임 {len(candidate_frames)}장 저장 완료")

    if candidate_frames:
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
    """세션의 MLLM 비교 결과를 조회한다.

    업로드된 적 없는 세션은 404로 끊는다 — pending으로 답하면 앱이 오타난 세션 ID를
    영원히 폴링하게 된다. 업로드는 됐으나 비교가 안 끝난 경우만 pending이다.
    """
    result = load_comparison_result(session_id)
    if result is not None:
        return CaptureResultResponse(
            status="done",
            improved=result.improved,
            reason=result.reason,
            retry_after_seconds=None,
        )

    if not session_exists(session_id):
        raise HTTPException(status_code=404, detail=f"세션 '{session_id}'을 찾을 수 없습니다")

    return CaptureResultResponse(
        status="pending",
        improved=None,
        reason=None,
        retry_after_seconds=RESULT_POLL_INTERVAL_SECONDS,
    )


def _parse_candidate_scores(candidate_scores: str, candidate_count: int) -> list[dict]:
    """JSON 문자열로 전달된 온디바이스 점수를 파싱·검증한다. 형식이 잘못되면 명확한 422로 실패한다."""
    try:
        parsed = json.loads(candidate_scores)
    except json.JSONDecodeError as exc:
        raise HTTPException(
            status_code=422, detail=f"candidate_scores가 올바른 JSON이 아닙니다: {exc}"
        ) from exc
    if not isinstance(parsed, list):
        raise HTTPException(status_code=422, detail="candidate_scores는 JSON 배열이어야 합니다")
    # 점수는 후보 순서대로 candidate_N에 매핑되므로, 개수가 어긋나면 엉뚱한 후보에 붙는다.
    # 빈 배열은 "점수를 아예 안 보냄"이라는 정상 케이스이므로 개수 검증에서 제외한다.
    if parsed and len(parsed) != candidate_count:
        raise HTTPException(
            status_code=422,
            detail=(
                f"candidate_scores 개수({len(parsed)})가 "
                f"후보 프레임 수({candidate_count})와 일치하지 않습니다"
            ),
        )
    return parsed
