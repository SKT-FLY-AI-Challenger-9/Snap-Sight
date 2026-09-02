# backend/api/labels.py
"""커스텀 라벨 등록 시 동의어 병합 판정 API 라우터 (실사용 피드백 2026-08-22).

앱이 라벨 부착 직전에 호출한다. 판정 실패가 라벨 등록을 막아서는 안 되므로
어떤 실패도 4xx/5xx로 돌려주지 않고 action="new"를 200으로 반환한다 —
앱은 응답이 없거나 실패하면 어차피 새 라벨로 저장한다(같은 폴백).
"""

from typing import Any

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool

from ai.label_normalizer import normalize_custom_label
from backend.api.guards import require_api_access
from backend.utils.logger import load_logger

logger = load_logger("labels.log")

router = APIRouter(dependencies=[Depends(require_api_access)])


class NormalizeLabelRequest(BaseModel):
    """POST /api/labels/normalize 요청 스키마."""

    label: str
    existing_labels: list[str] = []


@router.post("/api/labels/normalize")
async def normalize_label(request: NormalizeLabelRequest) -> dict[str, Any]:
    """새 라벨이 기존 커스텀 라벨의 동의어인지 판정한다.

    응답: {"action": "merge"|"new", "canonical_label": str|null}
    merge면 canonical_label은 반드시 existing_labels 안의 값이다.
    """
    try:
        decision = await run_in_threadpool(
            normalize_custom_label, request.label, request.existing_labels
        )
    except Exception as exc:  # noqa: BLE001 - 판정 실패가 라벨 등록을 막아서는 안 된다
        logger.error(f"라벨 병합 판정 실패({request.label!r}) — 새 라벨로 처리: {exc}")
        return {"action": "new", "canonical_label": None}

    logger.info(
        f"라벨 병합 판정: {request.label!r} → {decision.action}"
        + (f" ({decision.canonical_label!r})" if decision.action == "merge" else "")
    )
    return {"action": decision.action, "canonical_label": decision.canonical_label}
