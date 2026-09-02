# backend/api/session.py
"""Android(⑤)가 온디바이스 STT로 인식한 발화 텍스트를 받아 타겟 스펙으로 변환하는 API 라우터."""

from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool

from ai.llm_fallback import resolve_target_spec
from ai.target_spec import TargetSpec, TargetSpecSource, TargetSpecStatus
from backend.api.guards import require_api_access
from backend.storage.frame_buffer import validate_session_id
from backend.utils.logger import load_logger

logger = load_logger("session.log")

router = APIRouter(dependencies=[Depends(require_api_access)])


class UtteranceRequest(BaseModel):
    """POST /api/session/utterance 요청 스키마. 오디오가 아니라 STT 결과 텍스트를 받는다."""

    session_id: str
    raw_text: str


@router.post("/api/session/utterance")
async def parse_utterance(request: UtteranceRequest) -> dict[str, Any]:
    """발화 텍스트를 타겟 스펙으로 변환해 반환한다.

    규칙 기반 파싱이 저신뢰로 판정하면 resolve_target_spec이 LLM 폴백까지 태운다.
    응답은 ai/target_spec_schema.md 스키마 그대로이며, 앱의 TargetSpec.fromJsonOrNull이
    같은 계약으로 파싱한다.

    어떤 실패도 4xx/5xx로 돌려주지 않는다 — 앱은 요청이 실패하면 타겟 스펙 없는 일반 촬영
    모드로 넘어가버려서 "발화를 못 알아들었다"는 상태 자체가 사라진다. 대신 status=failed인
    스펙을 200으로 돌려줘 앱이 인식 실패 경로를 그대로 타게 한다.
    """
    try:
        validate_session_id(request.session_id)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    if not request.raw_text.strip():
        logger.info(f"세션 {request.session_id}: 빈 발화 — status=failed 스펙 반환")
        return _failed_spec(request.session_id, request.raw_text).to_dict()

    try:
        spec = await run_in_threadpool(resolve_target_spec, request.raw_text, request.session_id)
    except Exception as exc:  # noqa: BLE001 - 파싱 실패가 촬영 세션을 막아서는 안 된다
        logger.error(f"세션 {request.session_id}: 타겟 스펙 변환 실패 — {exc}")
        return _failed_spec(request.session_id, request.raw_text).to_dict()

    logger.info(
        f"세션 {request.session_id}: 타겟 스펙 변환 완료 — status={spec.status.value}, "
        f"subject_type={spec.subject_type.value}, confidence={spec.confidence}"
    )
    return spec.to_dict()


def _failed_spec(session_id: str, raw_text: str) -> TargetSpec:
    """발화를 해석하지 못했음을 나타내는 스펙을 만든다.

    status=failed는 "의도 자체가 없었던 경우"(spec 없음)와 구분되는 상태이며, 앱이 #33에서
    이미 같은 값으로 인식 실패를 처리하고 있다.
    """
    return TargetSpec(
        schema_version="0.2",
        session_id=session_id,
        raw_text=raw_text,
        status=TargetSpecStatus.FAILED,
        source=TargetSpecSource.ONDEVICE,
    )
