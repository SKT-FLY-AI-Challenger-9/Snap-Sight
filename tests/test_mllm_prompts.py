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
