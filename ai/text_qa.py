# ai/text_qa.py
"""사진 속에서 감지된 텍스트(메뉴판·안내문 등)에 대한 사용자 음성 질문에 답한다
(기능: 텍스트 감지 후 Q&A, 사용자 요청 2026-08-26).

ai/label_normalizer.py와 같은 패턴 — 저비용 모델(Haiku), 실패 시 None 폴백(호출부가
"잘 못 들었어요" 류로 안내).
"""

from __future__ import annotations

import logging

from anthropic import Anthropic, APIConnectionError, APIStatusError
from dotenv import load_dotenv

load_dotenv()

logger = logging.getLogger(__name__)

# 짧은 텍스트 기반 질의응답이라 저비용 모델로 충분하다 (label_normalizer.py와 동일 모델).
MODEL_ID = "claude-haiku-4-5-20251001"
MAX_TOKENS = 400
# 과도하게 긴 텍스트로 호출 비용이 튀지 않게 자른다 — 메뉴판·안내문 정도면 충분한 길이.
MAX_TEXT_LENGTH = 4000

SYSTEM_PROMPT = """당신은 시각장애인용 사진 앱의 텍스트 안내 도우미입니다.

사용자가 방금 찍은 사진에서 감지된 텍스트(메뉴판·안내문·표지판 등)와, 그 텍스트에 대한
사용자의 음성 질문이 주어집니다. 주어진 텍스트 안에서만 답을 찾아 한두 문장의 짧고
자연스러운 존댓말로 답하십시오.

- 텍스트에 답이 없으면 "그 내용은 텍스트에서 찾을 수 없어요"라고 솔직히 답하십시오.
- 텍스트를 그대로 읽어달라는 질문이면 관련 부분을 읽어주십시오.
- 추측하거나 텍스트에 없는 내용을 지어내지 마십시오."""

_USER_PROMPT_TEMPLATE = """감지된 텍스트:
{text}

사용자 질문: {question}"""


def answer_text_question(text: str, question: str) -> str | None:
    """감지된 텍스트를 근거로 질문에 답한다. 입력이 비었거나 호출 실패 시 None."""
    trimmed_text = text.strip()[:MAX_TEXT_LENGTH]
    trimmed_question = question.strip()
    if not trimmed_text or not trimmed_question:
        return None
    try:
        response = Anthropic().messages.create(
            model=MODEL_ID,
            max_tokens=MAX_TOKENS,
            system=SYSTEM_PROMPT,
            messages=[
                {
                    "role": "user",
                    "content": _USER_PROMPT_TEMPLATE.format(
                        text=trimmed_text, question=trimmed_question,
                    ),
                }
            ],
        )
    except (APIConnectionError, APIStatusError) as exc:
        logger.warning(f"텍스트 질문 응답 LLM 호출 실패: {exc}")
        return None
    answer = "".join(
        block.text for block in response.content if block.type == "text"
    ).strip()
    return answer or None
