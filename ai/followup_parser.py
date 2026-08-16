"""촬영 후 재시도 확인 발화("다시 찍을까요?"에 대한 응답)를 예/아니오로 분류한다.

③의 MLLM 후보 비교 결과가 Refine(재촬영 권유)일 때, ⑥이 "다시 찍을까요?" 같은 확인
질문을 안내하고, 사용자의 짧은 응답을 다시 STT로 캡처해 이 함수에 넘기는 흐름을 전제한다.
③의 Refine/Reject 판정 로직은 아직 레포에 없어서(2026-08-16 기준), 이 모듈은 그 판정
결과를 직접 참조하지 않고 "짧은 확인 발화 → 예/아니오"라는 좁은 문제만 독립적으로 푼다.
③ 인터페이스가 확정되면 이 함수의 반환값(bool | None)을 그쪽 판정 흐름에 연결하면 된다.
"""

from __future__ import annotations

# 부정 표현이 긍정 키워드의 substring이 되는 경우는 없지만(예: "아니"는 "네"를 포함하지
# 않음), 혼동 방지를 위해 부정을 먼저 확인한다.
NO_KEYWORDS = ["아니요", "아니오", "아니", "괜찮아", "됐어", "싫어", "그대로", "필요없어"]
YES_KEYWORDS = ["다시", "네", "예", "응", "그래", "좋아", "재촬영"]


def parse_followup_response(text: str) -> bool | None:
    """재시도 확인 발화를 해석한다.

    반환값: True=재시도(다시 찍기)를 원함, False=원치 않음(현재 컷 유지), None=판단 불가
    (재질문 필요 — 상위 로직이 다시 STT로 캡처하거나 기본 동작으로 폴백해야 함).
    """
    stripped = text.strip()
    if not stripped:
        return None

    for keyword in NO_KEYWORDS:
        if keyword in stripped:
            return False
    for keyword in YES_KEYWORDS:
        if keyword in stripped:
            return True
    return None


if __name__ == "__main__":
    for s in [
        "네 다시 찍어줘",
        "아니요 괜찮아요",
        "응",
        "그냥 이걸로 할게요",
        "음...",
    ]:
        print(s, "->", parse_followup_response(s))
