"""Capture upload and polling API for the Android client."""

from __future__ import annotations

import json
import math
import re

from fastapi import (
    APIRouter,
    BackgroundTasks,
    Depends,
    File,
    Form,
    HTTPException,
    Query,
    Response,
    UploadFile,
)
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from backend.api.guards import require_api_access
from backend.config import (
    RESULT_POLL_INTERVAL_SECONDS,
    load_capture_cleanup_batch_size,
    load_capture_ttl_seconds,
    load_max_candidate_frames,
    load_max_capture_file_bytes,
    load_max_capture_total_bytes,
)
from backend.mllm.description import label_photo_bytes, load_description
from backend.mllm.metadata import load_metadata
from backend.mllm.orchestration import trigger_capture_pipeline
from backend.storage.capture_state import (
    CaptureState,
    begin_capture_revision,
    cleanup_expired_capture_sessions,
    load_capture_state,
    read_capture_snapshot,
    run_if_current_revision,
)
from backend.storage.comparison_result import load_comparison_result
from backend.storage.frame_buffer import (
    load_session_frame_paths,
    save_candidate_frame,
    save_representative_frame,
    session_exists,
    validate_session_id,
)
from backend.storage.image_normalization import InvalidCaptureImage, normalize_uploaded_jpeg
from backend.utils.logger import load_logger

logger = load_logger("capture.log")
router = APIRouter(dependencies=[Depends(require_api_access)])

_READ_CHUNK_BYTES = 1024 * 1024
_JPEG_CONTENT_TYPES = {"image/jpeg", "image/jpg", "application/octet-stream"}
_OPAQUE_SUBJECT_REF = re.compile(r"local_[A-Za-z0-9_-]{1,74}\Z")


class CaptureFramesResponse(BaseModel):
    session_id: str
    received_candidate_count: int
    status: str
    capture_revision: int
    final_frame_id: str | None = None


class CaptureResultResponse(BaseModel):
    status: str
    improved: bool | None
    reason: str | None
    retry_after_seconds: int | None = None
    capture_revision: int | None = None
    final_frame_id: str | None = None


@router.post("/api/capture/frames", response_model=CaptureFramesResponse)
async def receive_capture_frames(
    background_tasks: BackgroundTasks,
    session_id: str = Form(...),
    raw_text: str = Form(default=""),
    representative_frame: UploadFile = File(...),
    candidate_frames: list[UploadFile] = File(default_factory=list),
    candidate_scores: str = Form(default="[]"),
    custom_labels: str = Form(default="[]"),
    detected_objects: str = Form(default="[]"),
    known_subjects: str = Form(default="[]"),
) -> CaptureFramesResponse:
    """Validate a complete upload, commit one revision, then enqueue its pipeline."""
    _validate_session_or_422(session_id)
    if len(candidate_frames) > load_max_candidate_frames():
        raise HTTPException(status_code=413, detail="too many candidate frames")

    scores = _parse_candidate_scores(candidate_scores, len(candidate_frames))
    parsed_custom_labels = _parse_string_list(custom_labels, "custom_labels")
    parsed_detected_objects = _parse_string_list(detected_objects, "detected_objects")
    parsed_known_subjects = _parse_known_subjects(known_subjects)

    # Buffer and validate every file before clearing the previous revision.
    max_file = load_max_capture_file_bytes()
    max_total = load_max_capture_total_bytes()
    total = 0
    representative_bytes = await _read_jpeg_upload(
        representative_frame, max_file_bytes=max_file, remaining_total_bytes=max_total
    )
    total += len(representative_bytes)
    try:
        normalized_representative = await run_in_threadpool(
            normalize_uploaded_jpeg, representative_bytes, 0
        )
    except InvalidCaptureImage as exc:
        raise HTTPException(status_code=415, detail=str(exc)) from exc
    if len(normalized_representative) > max_file:
        raise HTTPException(status_code=413, detail="normalized capture image is too large")
    total += len(normalized_representative) - len(representative_bytes)
    representative_bytes = normalized_representative
    candidate_bytes: list[bytes] = []
    for candidate in candidate_frames:
        content = await _read_jpeg_upload(
            candidate,
            max_file_bytes=max_file,
            remaining_total_bytes=max_total - total,
        )
        total += len(content)
        candidate_bytes.append(content)

    normalized_candidates: list[bytes] = []
    for index, content in enumerate(candidate_bytes):
        rotation_degrees = _candidate_rotation_degrees(scores, index)
        try:
            normalized = await run_in_threadpool(
                normalize_uploaded_jpeg, content, rotation_degrees
            )
        except InvalidCaptureImage as exc:
            raise HTTPException(status_code=415, detail=str(exc)) from exc
        if len(normalized) > max_file or total - len(content) + len(normalized) > max_total:
            raise HTTPException(status_code=413, detail="normalized capture upload is too large")
        total += len(normalized) - len(content)
        normalized_candidates.append(normalized)

    ttl_seconds = load_capture_ttl_seconds()
    if ttl_seconds > 0:
        await run_in_threadpool(
            cleanup_expired_capture_sessions,
            ttl_seconds,
            max_removals=load_capture_cleanup_batch_size(),
        )

    def _initialize_revision() -> None:
        # Client filenames are intentionally ignored: all accepted content is
        # JPEG and receives a server-owned deterministic path.
        save_representative_frame(session_id, "representative.jpg", representative_bytes)
        for index, content in enumerate(normalized_candidates):
            save_candidate_frame(session_id, index, "candidate.jpg", content)

    state = await run_in_threadpool(begin_capture_revision, session_id, _initialize_revision)
    background_tasks.add_task(
        trigger_capture_pipeline,
        session_id,
        state.capture_revision,
        raw_text,
        scores,
        parsed_custom_labels,
        parsed_detected_objects,
        parsed_known_subjects,
    )
    logger.info(
        f"Session {session_id} revision {state.capture_revision}: saved representative and "
        f"{len(candidate_frames)} candidates"
    )
    return CaptureFramesResponse(
        session_id=session_id,
        received_candidate_count=len(candidate_frames),
        status="saved",
        capture_revision=state.capture_revision,
        final_frame_id=None,
    )


class PhotoLabelResponse(BaseModel):
    category: str | None
    label: str | None
    description: str | None


@router.post("/api/photos/describe", response_model=PhotoLabelResponse)
async def describe_photo_upload(photo: UploadFile = File(...)) -> PhotoLabelResponse:
    image_bytes = await _read_jpeg_upload(
        photo,
        max_file_bytes=load_max_capture_file_bytes(),
        remaining_total_bytes=load_max_capture_total_bytes(),
    )
    try:
        image_bytes = await run_in_threadpool(normalize_uploaded_jpeg, image_bytes, 0)
    except InvalidCaptureImage as exc:
        raise HTTPException(status_code=415, detail=str(exc)) from exc
    if len(image_bytes) > load_max_capture_file_bytes():
        raise HTTPException(status_code=413, detail="normalized photo is too large")
    result = await run_in_threadpool(label_photo_bytes, image_bytes)
    return PhotoLabelResponse(
        category=result.category if result else None,
        label=result.label if result else None,
        description=result.description if result else None,
    )


class CaptureDescriptionResponse(BaseModel):
    status: str
    description: str | None
    retry_after_seconds: int | None = None
    capture_revision: int | None = None
    final_frame_id: str | None = None


@router.get("/api/capture/{session_id}/description", response_model=CaptureDescriptionResponse)
async def get_capture_description(session_id: str) -> CaptureDescriptionResponse:
    state, payload = _read_snapshot_or_422(session_id, lambda: load_description(session_id))
    if payload is not None:
        return CaptureDescriptionResponse(
            status="done",
            description=payload.get("description"),
            capture_revision=payload.get("capture_revision") or _revision(state),
            final_frame_id=payload.get("final_frame_id") or _final_frame(state),
        )
    _raise_if_missing(session_id)
    return CaptureDescriptionResponse(
        status=_pending_or_failed(state),
        description=None,
        retry_after_seconds=None if state and state.status == "failed" else 1,
        capture_revision=_revision(state),
        final_frame_id=_final_frame(state),
    )


class CaptureMetadataResponse(BaseModel):
    status: str
    taxonomy_version: int | None = None
    brief_description: str | None = None
    long_description: str | None = None
    labels: list[str] = Field(default_factory=list)
    custom_labels: list[str] = Field(default_factory=list)
    people_count: int | None = None
    has_text: bool = False
    text_topic: str | None = None
    text_content: str | None = None
    retry_after_seconds: int | None = None
    capture_revision: int | None = None
    final_frame_id: str | None = None


@router.get("/api/capture/{session_id}/metadata", response_model=CaptureMetadataResponse)
async def get_capture_metadata(session_id: str) -> CaptureMetadataResponse:
    state, payload = _read_snapshot_or_422(session_id, lambda: load_metadata(session_id))
    if payload is not None:
        return CaptureMetadataResponse(
            status="done",
            taxonomy_version=payload.get("taxonomy_version"),
            brief_description=payload.get("brief_description"),
            long_description=payload.get("long_description"),
            labels=payload.get("labels") or [],
            custom_labels=payload.get("custom_labels") or [],
            people_count=payload.get("people_count"),
            has_text=bool(payload.get("has_text")),
            text_topic=payload.get("text_topic"),
            text_content=payload.get("text_content"),
            capture_revision=payload.get("capture_revision") or _revision(state),
            final_frame_id=payload.get("final_frame_id") or _final_frame(state),
        )
    _raise_if_missing(session_id)
    return CaptureMetadataResponse(
        status=_pending_or_failed(state),
        retry_after_seconds=None if state and state.status == "failed" else 3,
        capture_revision=_revision(state),
        final_frame_id=_final_frame(state),
    )


@router.get("/api/capture/{session_id}/result", response_model=CaptureResultResponse)
async def get_capture_result(session_id: str) -> CaptureResultResponse:
    state, result = _read_snapshot_or_422(
        session_id, lambda: load_comparison_result(session_id)
    )
    if result is not None:
        return CaptureResultResponse(
            status="done",
            improved=result.improved,
            reason=result.reason,
            capture_revision=_revision(state),
            final_frame_id=_final_frame(state),
        )
    _raise_if_missing(session_id)
    return CaptureResultResponse(
        status=_pending_or_failed(state),
        improved=None,
        reason="capture pipeline failed" if state and state.status == "failed" else None,
        retry_after_seconds=(
            None if state and state.status == "failed" else RESULT_POLL_INTERVAL_SECONDS
        ),
        capture_revision=_revision(state),
        final_frame_id=_final_frame(state),
    )


@router.get("/api/capture/{session_id}/final-frame")
async def get_capture_final_frame(
    session_id: str,
    capture_revision: int | None = Query(default=None, ge=1),
) -> Response:
    """Return the canonical JPEG selected for an exact capture revision.

    Clients should pass the revision returned by the result endpoint. A reused
    session id then yields 409 instead of silently returning another capture.
    Revision and source-frame identity are repeated in response headers because
    the response body is binary.
    """
    state = _load_state_or_422(session_id)
    _raise_if_missing(session_id)
    if state is None or state.final_frame_id is None:
        raise HTTPException(status_code=409, detail="canonical final frame is not ready")
    if capture_revision is not None and capture_revision != state.capture_revision:
        raise HTTPException(status_code=409, detail="capture revision no longer matches")
    def _read_revision_frame() -> bytes:
        representative, _ = load_session_frame_paths(session_id)
        if representative.stat().st_size > load_max_capture_file_bytes():
            raise HTTPException(status_code=413, detail="canonical final frame is too large")
        return representative.read_bytes()

    try:
        still_current, content = await run_in_threadpool(
            run_if_current_revision,
            session_id,
            state.capture_revision,
            _read_revision_frame,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="canonical final frame is missing") from exc
    if not still_current or content is None:
        raise HTTPException(status_code=409, detail="capture revision no longer matches")
    if not content.startswith(b"\xff\xd8\xff"):
        raise HTTPException(status_code=500, detail="canonical final frame is not JPEG")
    return Response(
        content=content,
        media_type="image/jpeg",
        headers={
            "X-Capture-Revision": str(state.capture_revision),
            "X-Final-Frame-Id": state.final_frame_id,
            "Cache-Control": "private, no-store",
        },
    )


async def _read_jpeg_upload(
    upload: UploadFile,
    *,
    max_file_bytes: int,
    remaining_total_bytes: int,
) -> bytes:
    media_type = (upload.content_type or "").split(";", 1)[0].strip().lower()
    if media_type and media_type not in _JPEG_CONTENT_TYPES:
        raise HTTPException(status_code=415, detail="capture frames must be JPEG images")
    if remaining_total_bytes <= 0:
        raise HTTPException(status_code=413, detail="capture upload is too large")

    content = bytearray()
    while True:
        chunk = await upload.read(_READ_CHUNK_BYTES)
        if not chunk:
            break
        content.extend(chunk)
        if len(content) > max_file_bytes:
            raise HTTPException(status_code=413, detail="capture image is too large")
        if len(content) > remaining_total_bytes:
            raise HTTPException(status_code=413, detail="capture upload is too large")
    if not content.startswith(b"\xff\xd8\xff"):
        raise HTTPException(status_code=415, detail="capture frame is not a JPEG image")
    return bytes(content)


def _validate_session_or_422(session_id: str) -> str:
    try:
        return validate_session_id(session_id)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


def _load_state_or_422(session_id: str) -> CaptureState | None:
    _validate_session_or_422(session_id)
    return load_capture_state(session_id)


def _read_snapshot_or_422(session_id: str, reader):
    _validate_session_or_422(session_id)
    return read_capture_snapshot(session_id, reader)


def _raise_if_missing(session_id: str) -> None:
    if not session_exists(session_id):
        raise HTTPException(status_code=404, detail=f"capture session {session_id!r} not found")


def _pending_or_failed(state: CaptureState | None) -> str:
    return "failed" if state is not None and state.status == "failed" else "pending"


def _revision(state: CaptureState | None) -> int | None:
    return state.capture_revision if state is not None else None


def _final_frame(state: CaptureState | None) -> str | None:
    return state.final_frame_id if state is not None else None


def _parse_string_list(raw: str, field_name: str) -> list[str]:
    parsed = _parse_json(raw, field_name)
    if not isinstance(parsed, list) or any(not isinstance(item, str) for item in parsed):
        raise HTTPException(status_code=422, detail=f"{field_name} must be a JSON string array")
    if len(parsed) > 256 or any(len(item) > 256 for item in parsed):
        raise HTTPException(status_code=422, detail=f"{field_name} is too large")
    return parsed


def _parse_known_subjects(raw: str) -> list[dict]:
    parsed = _parse_json(raw, "known_subjects")
    if not isinstance(parsed, list) or len(parsed) > 64:
        raise HTTPException(status_code=422, detail="known_subjects must be a JSON array")
    subjects: list[dict] = []
    for item in parsed:
        if not isinstance(item, dict):
            raise HTTPException(status_code=422, detail="known_subjects entries must be objects")
        name = item.get("name")
        subject_ref = item.get("subject_ref")
        has_legacy_name = isinstance(name, str) and bool(name.strip())
        has_opaque_ref = isinstance(subject_ref, str) and bool(
            _OPAQUE_SUBJECT_REF.fullmatch(subject_ref)
        )
        if not has_legacy_name and not has_opaque_ref:
            raise HTTPException(
                status_code=422,
                detail="known_subjects requires a legacy name or opaque local_* subject_ref",
            )
        if has_legacy_name and len(name.strip()) > 128:
            raise HTTPException(status_code=422, detail="known_subjects.name is too long")
        kind = str(item.get("kind") or "object")
        if kind not in {"person", "object"}:
            raise HTTPException(status_code=422, detail="known_subjects.kind is invalid")
        bbox = _validate_bbox(item.get("bbox"))
        intent_target = item.get("intent_target", False)
        if not isinstance(intent_target, bool):
            raise HTTPException(
                status_code=422, detail="known_subjects.intent_target must be a boolean"
            )
        named = item.get("named", True)
        if not isinstance(named, bool):
            raise HTTPException(status_code=422, detail="known_subjects.named must be a boolean")
        subject = {"kind": kind, "bbox": bbox}
        if intent_target:
            # 발화 의도가 이 등록 대상을 가리켰다는 표시 — MLLM 설명이 이 대상 중심으로
            # 서술되는 근거다. 이름은 여전히 기기 밖으로 나오지 않는다.
            subject["intent_target"] = True
        if not named:
            # 기기에 이름 매핑이 없는 참조 — 프롬프트에서 토큰 대신 "요청한 촬영 대상"으로
            # 다뤄 토큰이 설명 문장에 노출되지 않게 한다.
            subject["named"] = False
        if has_legacy_name:
            subject["name"] = name.strip()
        else:
            # New clients keep real names on-device and send only an opaque,
            # session-scoped reference. It is retained for correlation but is
            # never rendered into an MLLM prompt as a person's name.
            subject["subject_ref"] = subject_ref
        subjects.append(subject)
    return subjects


def _validate_bbox(raw_bbox: object) -> dict | None:
    if raw_bbox is None:
        return None
    if not isinstance(raw_bbox, dict):
        raise HTTPException(status_code=422, detail="known_subjects.bbox must be an object")
    keys = ("x_min", "y_min", "x_max", "y_max")
    values: dict[str, float] = {}
    for key in keys:
        value = raw_bbox.get(key)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise HTTPException(status_code=422, detail="known_subjects.bbox must be numeric")
        number = float(value)
        if not math.isfinite(number) or not 0 <= number <= 1:
            raise HTTPException(status_code=422, detail="known_subjects.bbox must be normalized")
        values[key] = number
    if values["x_min"] >= values["x_max"] or values["y_min"] >= values["y_max"]:
        raise HTTPException(status_code=422, detail="known_subjects.bbox has invalid bounds")
    return values


def _parse_candidate_scores(candidate_scores: str, candidate_count: int) -> list[dict]:
    parsed = _parse_json(candidate_scores, "candidate_scores")
    if not isinstance(parsed, list):
        raise HTTPException(status_code=422, detail="candidate_scores must be a JSON array")
    if parsed and len(parsed) != candidate_count:
        raise HTTPException(
            status_code=422,
            detail=(
                f"candidate_scores count ({len(parsed)}) does not match "
                f"candidate frame count ({candidate_count})"
            ),
        )
    for entry in parsed:
        if not isinstance(entry, dict):
            raise HTTPException(status_code=422, detail="candidate_scores entries must be objects")
        for key, value in entry.items():
            if len(str(key)) > 64:
                raise HTTPException(status_code=422, detail="candidate score key is too long")
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise HTTPException(status_code=422, detail="candidate scores must be numeric")
            if not math.isfinite(float(value)):
                raise HTTPException(status_code=422, detail="candidate scores must be finite")
        _candidate_rotation_degrees([entry], 0)
        for unit_key in ("blur_score", "eyes_closed_score"):
            if unit_key in entry and not 0 <= float(entry[unit_key]) <= 1:
                raise HTTPException(
                    status_code=422, detail=f"{unit_key} must be between 0 and 1"
                )
    return parsed


def _candidate_rotation_degrees(scores: list[dict], index: int) -> int:
    if not scores or index >= len(scores):
        return 0
    raw_rotation = scores[index].get("rotation_degrees", 0)
    if isinstance(raw_rotation, bool) or not isinstance(raw_rotation, (int, float)):
        raise HTTPException(status_code=422, detail="rotation_degrees must be numeric")
    rotation = int(raw_rotation)
    if float(raw_rotation) != rotation or rotation not in {0, 90, 180, 270}:
        raise HTTPException(
            status_code=422, detail="rotation_degrees must be one of 0, 90, 180 or 270"
        )
    return rotation


def _parse_json(raw: str, field_name: str) -> object:
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError) as exc:
        raise HTTPException(status_code=422, detail=f"{field_name} is not valid JSON") from exc
