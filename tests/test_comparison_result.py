# tests/test_comparison_result.py
"""backend.storage.comparison_result의 저장·조회 로직을 확인하는 테스트."""

from backend.mllm.prompts import FrameComparisonResult
from backend.storage.comparison_result import load_comparison_result, save_comparison_result


def test_save_and_load_comparison_result_round_trips(tmp_path, monkeypatch):
    """저장한 결과를 그대로 다시 읽어올 수 있다."""
    monkeypatch.chdir(tmp_path)
    result = FrameComparisonResult(
        improved=True, selected_frame="candidate_1", reason="더 낫습니다"
    )

    save_comparison_result("session-1", result)
    loaded = load_comparison_result("session-1")

    assert loaded == result


def test_load_comparison_result_returns_none_when_missing(tmp_path, monkeypatch):
    """결과가 저장된 적 없는 세션은 None을 반환한다."""
    monkeypatch.chdir(tmp_path)

    assert load_comparison_result("no-such-session") is None
