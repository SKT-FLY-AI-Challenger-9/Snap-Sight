# tests/test_orchestration.py
"""backend.mllm.orchestration의 비동기 트리거·대표 컷 교체 로직을 확인하는 테스트."""

from backend.mllm.orchestration import trigger_comparison
from backend.mllm.prompts import FrameComparisonResult
from backend.storage.comparison_result import load_comparison_result
from backend.storage.frame_buffer import save_candidate_frame, save_representative_frame

REPRESENTATIVE_BYTES = b"representative-bytes"
CANDIDATE_0_BYTES = b"candidate-0-bytes"
CANDIDATE_1_BYTES = b"candidate-1-bytes"


def _save_session_frames(session_id: str) -> None:
    save_representative_frame(session_id, "rep.jpg", REPRESENTATIVE_BYTES)
    save_candidate_frame(session_id, 0, "cand0.jpg", CANDIDATE_0_BYTES)
    save_candidate_frame(session_id, 1, "cand1.jpg", CANDIDATE_1_BYTES)


def test_trigger_comparison_saves_result_and_keeps_representative_when_not_improved(
    tmp_path, monkeypatch
):
    """개선 없음 판정이면 결과만 저장하고 대표 컷 파일은 그대로 둔다."""
    monkeypatch.chdir(tmp_path)
    _save_session_frames("session-keep")
    monkeypatch.setattr(
        "backend.mllm.orchestration.compare_candidate_frames",
        lambda *args, **kwargs: FrameComparisonResult(
            improved=False, selected_frame=None, reason="개선 없음"
        ),
    )

    trigger_comparison("session-keep", "인물 사진 찍어줘")

    result = load_comparison_result("session-keep")
    assert result.improved is False
    representative_path = tmp_path / "captures" / "session-keep" / "representative.jpg"
    assert representative_path.read_bytes() == REPRESENTATIVE_BYTES


def test_trigger_comparison_replaces_representative_when_improved(tmp_path, monkeypatch):
    """개선 판정이면 선택된 후보 내용으로 대표 컷 파일을 교체한다."""
    monkeypatch.chdir(tmp_path)
    _save_session_frames("session-replace")
    monkeypatch.setattr(
        "backend.mllm.orchestration.compare_candidate_frames",
        lambda *args, **kwargs: FrameComparisonResult(
            improved=True, selected_frame="candidate_2", reason="눈을 뜨고 있음"
        ),
    )

    trigger_comparison("session-replace", "인물 사진 찍어줘")

    representative_path = tmp_path / "captures" / "session-replace" / "representative.jpg"
    assert representative_path.read_bytes() == CANDIDATE_1_BYTES


def test_trigger_comparison_ignores_out_of_range_selected_frame(tmp_path, monkeypatch):
    """selected_frame이 실제 후보 범위를 벗어나면 교체하지 않고, 결과도 improved=False로 저장한다."""
    monkeypatch.chdir(tmp_path)
    _save_session_frames("session-out-of-range")
    monkeypatch.setattr(
        "backend.mllm.orchestration.compare_candidate_frames",
        lambda *args, **kwargs: FrameComparisonResult(
            improved=True, selected_frame="candidate_99", reason="범위 밖"
        ),
    )

    trigger_comparison("session-out-of-range", "인물 사진 찍어줘")

    representative_path = tmp_path / "captures" / "session-out-of-range" / "representative.jpg"
    assert representative_path.read_bytes() == REPRESENTATIVE_BYTES
    # 교체하지 못했으므로 조회 API가 "교체됨"이라고 보고하면 안 된다.
    result = load_comparison_result("session-out-of-range")
    assert result.improved is False
    assert result.selected_frame is None


def test_trigger_comparison_saves_fallback_result_when_comparison_raises(tmp_path, monkeypatch):
    """MLLM 비교가 예외를 던져도 예외를 밖으로 흘리지 않고 improved=False 결과를 저장한다."""
    monkeypatch.chdir(tmp_path)
    _save_session_frames("session-raises")

    def _raise(*args, **kwargs):
        raise RuntimeError("MLLM 응답 스키마 불일치")

    monkeypatch.setattr("backend.mllm.orchestration.compare_candidate_frames", _raise)

    trigger_comparison("session-raises", "인물 사진 찍어줘")

    result = load_comparison_result("session-raises")
    assert result.improved is False
    assert "MLLM 응답 스키마 불일치" in result.reason
    representative_path = tmp_path / "captures" / "session-raises" / "representative.jpg"
    assert representative_path.read_bytes() == REPRESENTATIVE_BYTES


def test_trigger_comparison_saves_fallback_result_when_frames_missing(tmp_path, monkeypatch):
    """저장된 프레임을 찾지 못해도 결과를 남겨 조회 API가 영구 pending에 빠지지 않게 한다."""
    monkeypatch.chdir(tmp_path)

    trigger_comparison("session-no-frames", "인물 사진 찍어줘")

    result = load_comparison_result("session-no-frames")
    assert result is not None
    assert result.improved is False


def test_trigger_comparison_replaces_with_matching_suffix_when_formats_differ(
    tmp_path, monkeypatch
):
    """후보 확장자가 대표 컷과 다르면 확장자를 맞춰 저장하고 이전 대표 컷 파일은 남기지 않는다."""
    monkeypatch.chdir(tmp_path)
    save_representative_frame("session-suffix", "rep.jpg", REPRESENTATIVE_BYTES)
    save_candidate_frame("session-suffix", 0, "cand0.png", CANDIDATE_0_BYTES)
    monkeypatch.setattr(
        "backend.mllm.orchestration.compare_candidate_frames",
        lambda *args, **kwargs: FrameComparisonResult(
            improved=True, selected_frame="candidate_1", reason="눈을 뜨고 있음"
        ),
    )

    trigger_comparison("session-suffix", "인물 사진 찍어줘")

    session_dir = tmp_path / "captures" / "session-suffix"
    assert (session_dir / "representative.png").read_bytes() == CANDIDATE_0_BYTES
    assert not (session_dir / "representative.jpg").exists()


def test_trigger_comparison_passes_candidate_scores_through(tmp_path, monkeypatch):
    """전달받은 candidate_scores를 그대로 compare_candidate_frames에 넘긴다."""
    monkeypatch.chdir(tmp_path)
    _save_session_frames("session-scores")
    received = {}

    def _fake_compare(raw_text, structured_requirements, representative, candidates, **kwargs):
        received["candidate_scores"] = kwargs.get("candidate_scores")
        return FrameComparisonResult(improved=False, selected_frame=None, reason="개선 없음")

    monkeypatch.setattr("backend.mllm.orchestration.compare_candidate_frames", _fake_compare)

    scores = [{"eyes_closed_score": 0.2}, {"eyes_closed_score": 0.9}]
    trigger_comparison("session-scores", "인물 사진 찍어줘", candidate_scores=scores)

    assert received["candidate_scores"] == scores
