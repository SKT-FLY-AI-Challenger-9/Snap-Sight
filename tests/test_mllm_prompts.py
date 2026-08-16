"""backend/mllm/prompts.py에 대한 네트워크 없는 단위 테스트."""

import pytest

from backend.mllm.prompts import FrameComparisonResult, build_comparison_prompt


def test_build_comparison_prompt_includes_raw_text():
    prompt = build_comparison_prompt("인물 사진 찍어줘", {})
    assert "인물 사진 찍어줘" in prompt


def test_build_comparison_prompt_includes_each_structured_requirement():
    prompt = build_comparison_prompt(
        "두 명이 같이 나오게 찍어줘",
        {"인원수": "2명", "구도": "클로즈업"},
    )
    assert "인원수" in prompt and "2명" in prompt
    assert "구도" in prompt and "클로즈업" in prompt


def test_build_comparison_prompt_without_scores_says_none_available():
    prompt = build_comparison_prompt("인물 사진 찍어줘", {})
    assert "온디바이스 사전 점수 없음" in prompt


def test_build_comparison_prompt_includes_eyes_closed_scores_labeled_by_candidate_number():
    prompt = build_comparison_prompt(
        "인물 사진 찍어줘",
        {},
        candidate_scores=[{"eyes_closed_score": 0.12}, {"eyes_closed_score": 0.83}],
    )
    assert "candidate_1: 눈감음 의심도 0.12" in prompt
    assert "candidate_2: 눈감음 의심도 0.83" in prompt


def test_build_comparison_prompt_never_exposes_blur_score():
    prompt = build_comparison_prompt(
        "인물 사진 찍어줘",
        {},
        candidate_scores=[{"eyes_closed_score": 0.1, "blur_score": 0.77}],
    )
    assert "0.77" not in prompt
    assert "blur" not in prompt.lower()


def test_build_comparison_prompt_tells_model_to_use_scores_only_at_step_three():
    prompt = build_comparison_prompt("인물 사진 찍어줘", {})
    assert "3단계" in prompt
    assert "1·2단계" in prompt


@pytest.mark.parametrize(
    "invalid_value",
    [
        "높음",
        -0.1,
        1.1,
        True,
        None,
    ],
)
def test_build_comparison_prompt_ignores_invalid_eyes_closed_score(invalid_value):
    prompt = build_comparison_prompt(
        "인물 사진 찍어줘",
        {},
        candidate_scores=[{"eyes_closed_score": invalid_value}],
    )
    assert "온디바이스 사전 점수 없음" in prompt
    assert "candidate_1: 눈감음 의심도" not in prompt


def test_build_comparison_prompt_accepts_boundary_eyes_closed_scores():
    prompt = build_comparison_prompt(
        "인물 사진 찍어줘",
        {},
        candidate_scores=[{"eyes_closed_score": 0.0}, {"eyes_closed_score": 1.0}],
    )
    assert "candidate_1: 눈감음 의심도 0.0" in prompt
    assert "candidate_2: 눈감음 의심도 1.0" in prompt


def test_result_rejects_improved_true_with_no_selected_frame():
    with pytest.raises(ValueError):
        FrameComparisonResult(improved=True, selected_frame=None, reason="사유")


def test_result_rejects_improved_false_with_selected_frame():
    with pytest.raises(ValueError):
        FrameComparisonResult(improved=False, selected_frame="candidate_1", reason="사유")


def test_result_accepts_improved_true_with_selected_frame():
    result = FrameComparisonResult(improved=True, selected_frame="candidate_1", reason="사유")
    assert result.improved is True
    assert result.selected_frame == "candidate_1"


def test_result_accepts_improved_false_with_no_selected_frame():
    result = FrameComparisonResult(improved=False, selected_frame=None, reason="사유")
    assert result.improved is False
    assert result.selected_frame is None
