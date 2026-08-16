# tests/test_followup_parser.py
"""ai/followup_parser.parse_followup_response의 예/아니오 판정을 검증하는 테스트."""

from ai.followup_parser import NO_KEYWORDS, YES_KEYWORDS, parse_followup_response


def test_no_keyword_is_shared_between_yes_and_no_lists():
    """긍정/부정 키워드가 겹치면(substring 포함 관계여도) 오판정으로 이어진다.
    ai/slot_parser.py에서 겪었던 것과 같은 종류의 버그를 여기서도 미리 막는다."""
    for no_kw in NO_KEYWORDS:
        for yes_kw in YES_KEYWORDS:
            assert no_kw not in yes_kw and yes_kw not in no_kw, (
                f"'{no_kw}'(아니오)와 '{yes_kw}'(예)가 서로 포함 관계라 오판정 위험"
            )


def test_yes_responses_recognized():
    for text in ["네", "네 다시 찍어줘", "응 다시", "그래 다시 찍자", "좋아요", "재촬영 할게요"]:
        assert parse_followup_response(text) is True, f"{text!r} -> True 기대"


def test_no_responses_recognized():
    for text in ["아니요", "아니요 괜찮아요", "아니 됐어", "그대로 저장할게요", "필요없어요"]:
        assert parse_followup_response(text) is False, f"{text!r} -> False 기대"


def test_ambiguous_or_empty_text_returns_none():
    for text in ["", "   ", "음...", "글쎄요", "그냥 이걸로 할게요"]:
        assert parse_followup_response(text) is None, f"{text!r} -> None 기대"


def test_negative_keyword_checked_before_positive_to_avoid_false_yes():
    """"아니"는 부정이지만, 만약 순서가 잘못되면 그 안에 긍정 키워드가 섞여 있을 때
    잘못 True로 판정될 수 있다 — 지금 키워드셋에는 그런 substring이 없지만, 부정을
    먼저 검사하는 구현 순서 자체를 문서화해 회귀를 방지한다."""
    assert parse_followup_response("아니요, 다시 안 찍어도 돼요") is False
