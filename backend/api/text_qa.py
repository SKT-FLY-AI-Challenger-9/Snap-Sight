# backend/api/text_qa.py
"""사진에서 감지된 텍스트에 대한 음성 질문 응답 API 라우터 (사용자 요청 2026-08-26).

앱이 결과 화면에서 "텍스트에서 필요하신 정보 있으실까요?" 질문 뒤 인식한 발화를
감지된 텍스트와 함께 보낸다. 판정 실패가 안내를 막아서는 안 되므로 4xx/5xx 대신
answer=null 을 200으로 반환한다 — 앱은 null 이면 "잘 못 들었어요"류로 안내한다.
"""

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool

from ai.text_qa import answer_text_question
from backend.api.guards import require_api_access
from backend.utils.logger import load_logger

logger = load_logger("text_qa.log")

router = APIRouter(dependencies=[Depends(require_api_access)])


class TextQaRequest(BaseModel):
    """POST /api/text/ask 요청 스키마."""

    text: str
    question: str


class TextQaResponse(BaseModel):
    answer: str | None = None


@router.post("/api/text/ask", response_model=TextQaResponse)
async def ask_about_text(request: TextQaRequest) -> TextQaResponse:
    """사진에서 감지된 텍스트를 근거로 사용자 질문에 답한다. 실패 시 answer=null."""
    try:
        answer = await run_in_threadpool(
            answer_text_question, request.text, request.question
        )
    except Exception as exc:  # noqa: BLE001 - 응답 실패가 세션을 막아서는 안 된다
        logger.error(f"텍스트 질문 응답 실패: {exc}")
        return TextQaResponse(answer=None)

    logger.info(f"텍스트 질문 응답: {request.question!r} -> {'OK' if answer else 'None'}")
    return TextQaResponse(answer=answer)
