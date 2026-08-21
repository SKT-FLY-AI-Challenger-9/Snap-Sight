# backend/api/capture.py
"""Android(⑤)가 촬영 시점에 로컬 버퍼에서 전송하는 대표 컷·후보 프레임을 받아 저장하고,
저장 완료 후 MLLM 후보 비교를 비동기로 트리거하는 API 라우터."""

import json
import threading

from fastapi import APIRouter, BackgroundTasks, File, Form, HTTPException, UploadFile
from pydantic import BaseModel

from backend.config import RESULT_POLL_INTERVAL_SECONDS
from backend.mllm.description import label_photo_bytes, load_description, trigger_description
from backend.mllm.metadata import load_metadata, trigger_metadata
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
    # 검색용 메타데이터(기능 3-B) 재료 — 없어도 정상 (구버전 앱, 커스텀 라벨 없음 등)
    custom_labels: str = Form(default="[]"),
    detected_objects: str = Form(default="[]"),
) -> CaptureFramesResponse:
    """대표 컷 1장과 후보 프레임 목록을 저장하고, 후보가 있으면 MLLM 비교를 비동기로 트리거한다."""
    # 파일을 디스크에 쓰기 전에 검증한다 — 저장 후 422로 실패하면 재시도 때 잔여 파일이 남는다.
    scores = _parse_candidate_scores(candidate_scores, len(candidate_frames))
    parsed_custom_labels = _parse_string_list(custom_labels, "custom_labels")
    parsed_detected_objects = _parse_string_list(detected_objects, "detected_objects")

    representative_bytes = await representative_frame.read()
    save_representative_frame(session_id, representative_frame.filename, representative_bytes)

    for index, candidate in enumerate(candidate_frames):
        candidate_bytes = await candidate.read()
        save_candidate_frame(session_id, index, candidate.filename, candidate_bytes)

    logger.info(f"세션 {session_id}: 대표 컷 1장, 후보 프레임 {len(candidate_frames)}장 저장 완료")

    # 한 줄 사진 설명(Haiku)은 별도 스레드로 — BackgroundTasks는 순차 실행이라 비교와 병렬이 안 된다 (#76)
    threading.Thread(target=trigger_description, args=(session_id,), daemon=True).start()

    # 검색용 상세 메타데이터(기능 3-B)도 별도 스레드 — 느려도 되므로 즉시 설명·비교와 병렬로 돈다
    threading.Thread(
        target=trigger_metadata,
        args=(session_id, raw_text, parsed_custom_labels, parsed_detected_objects),
        daemon=True,
    ).start()

    if candidate_frames:
        background_tasks.add_task(trigger_comparison, session_id, raw_text, scores)
    else:
        logger.info(f"세션 {session_id}: 후보 프레임 없음 — MLLM 비교 스킵")

    return CaptureFramesResponse(
        session_id=session_id,
        received_candidate_count=len(candidate_frames),
        status="saved",
    )


class PhotoLabelResponse(BaseModel):
    """POST /api/photos/describe 응답 스키마 — 사진첩 카드용 대분류·라벨·설명."""

    category: str | None
    label: str | None
    description: str | None


@router.post("/api/photos/describe", response_model=PhotoLabelResponse)
async def describe_photo_upload(photo: UploadFile = File(...)) -> PhotoLabelResponse:
    """사진 한 장을 받아 사진첩 카드용 라벨('장소·피사체')과 설명을 생성한다 (#78 라벨링).

    동기 호출이다 — 앱 사진첩 로더가 카드별로 순차 요청·캐시하므로 폴링 규약이 필요 없다.
    생성 실패는 null 필드로 반환한다 (앱은 자리표시 유지)."""
    image_bytes = await photo.read()
    result = label_photo_bytes(image_bytes)
    return PhotoLabelResponse(
        category=result.category if result else None,
        label=result.label if result else None,
        description=result.description if result else None,
    )


class CaptureDescriptionResponse(BaseModel):
    """GET /api/capture/{session_id}/description 응답 스키마."""

    status: str
    description: str | None
    retry_after_seconds: int | None = None


@router.get("/api/capture/{session_id}/description", response_model=CaptureDescriptionResponse)
async def get_capture_description(session_id: str) -> CaptureDescriptionResponse:
    """대표 컷 한 줄 설명을 조회한다. 규약은 result와 동일 — 미존재 세션 404, 생성 중 pending."""
    payload = load_description(session_id)
    if payload is not None:
        return CaptureDescriptionResponse(
            status="done", description=payload.get("description"), retry_after_seconds=None
        )

    if not session_exists(session_id):
        raise HTTPException(status_code=404, detail=f"세션 '{session_id}'을 찾을 수 없습니다")

    return CaptureDescriptionResponse(
        status="pending", description=None, retry_after_seconds=1
    )


class CaptureMetadataResponse(BaseModel):
    """GET /api/capture/{session_id}/metadata 응답 스키마 (기능 3-B).

    앱은 done 수신 후 로컬 사진 인덱스에 저장해 오프라인 검색·상세 낭독에 쓴다.
    """

    status: str
    taxonomy_version: int | None = None
    long_description: str | None = None
    labels: list[str] = []
    custom_labels: list[str] = []
    people_count: int | None = None
    retry_after_seconds: int | None = None


@router.get("/api/capture/{session_id}/metadata", response_model=CaptureMetadataResponse)
async def get_capture_metadata(session_id: str) -> CaptureMetadataResponse:
    """검색용 상세 메타데이터를 조회한다. 규약은 description/result 와 동일 — 미존재 404, 생성 중 pending."""
    payload = load_metadata(session_id)
    if payload is not None:
        return CaptureMetadataResponse(
            status="done",
            taxonomy_version=payload.get("taxonomy_version"),
            long_description=payload.get("long_description"),
            labels=payload.get("labels") or [],
            custom_labels=payload.get("custom_labels") or [],
            people_count=payload.get("people_count"),
        )

    if not session_exists(session_id):
        raise HTTPException(status_code=404, detail=f"세션 '{session_id}'을 찾을 수 없습니다")

    # 상세 메타데이터는 상위 모델이라 즉시 설명보다 오래 걸린다 — 여유 있는 재조회 간격
    return CaptureMetadataResponse(status="pending", retry_after_seconds=3)


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


def _parse_string_list(raw: str, field_name: str) -> list[str]:
    """JSON 문자열 배열 폼 필드를 파싱·검증한다. 형식이 잘못되면 명확한 422로 실패한다."""
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise HTTPException(
            status_code=422, detail=f"{field_name}가 올바른 JSON이 아닙니다: {exc}"
        ) from exc
    if not isinstance(parsed, list) or any(not isinstance(item, str) for item in parsed):
        raise HTTPException(status_code=422, detail=f"{field_name}는 문자열 JSON 배열이어야 합니다")
    return parsed


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
