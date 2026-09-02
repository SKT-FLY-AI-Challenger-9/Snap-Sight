"""Canonical-frame ordering and revision race tests."""

from backend.mllm.description import load_description
from backend.mllm.metadata import PhotoMetadataOutput, load_metadata
from backend.mllm.orchestration import trigger_capture_pipeline
from backend.mllm.prompts import FrameComparisonResult
from backend.storage.capture_state import begin_capture_revision, load_capture_state
from backend.storage.comparison_result import load_comparison_result
from backend.storage.frame_buffer import (
    load_session_frame_paths,
    save_candidate_frame,
    save_representative_frame,
)


def _begin_with_frames(session_id: str, *, candidate: bytes | None = None) -> int:
    def initialize() -> None:
        save_representative_frame(session_id, "frame.jpg", b"original-frame")
        if candidate is not None:
            save_candidate_frame(session_id, 0, "candidate.jpg", candidate)

    return begin_capture_revision(session_id, initialize).capture_revision


def test_pipeline_describes_exact_replaced_frame_and_shares_structured_result(
    tmp_path, monkeypatch
):
    monkeypatch.chdir(tmp_path)
    revision = _begin_with_frames("canonical", candidate=b"better-frame")
    monkeypatch.setattr(
        "backend.mllm.orchestration.compare_candidate_frames",
        lambda *args, **kwargs: FrameComparisonResult(
            improved=True, selected_frame="candidate_1", reason="better"
        ),
    )
    seen = {}

    def fake_understanding(image_path, **kwargs):
        seen["bytes"] = image_path.read_bytes()
        return PhotoMetadataOutput(
            brief_description="한 사람이 창가에 서 있어요.",
            long_description="한 사람이 창가에 서 있어요. 뒤에는 밝은 창문이 보여요.",
            labels=[],
            people_count=1,
        )

    monkeypatch.setattr("backend.mllm.orchestration.generate_metadata", fake_understanding)
    trigger_capture_pipeline("canonical", revision, "사진 찍어줘")

    representative, _ = load_session_frame_paths("canonical")
    description = load_description("canonical")
    metadata = load_metadata("canonical")
    state = load_capture_state("canonical")
    assert seen["bytes"] == b"better-frame"
    assert representative.read_bytes() == b"better-frame"
    assert description["description"] == metadata["brief_description"]
    assert description["capture_revision"] == metadata["capture_revision"] == revision
    assert description["final_frame_id"] == metadata["final_frame_id"] == "candidate_1"
    assert state.status == "ready"
    assert state.final_frame_id == "candidate_1"


def test_zero_candidate_pipeline_is_terminal_and_uses_representative(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    revision = _begin_with_frames("representative-only")
    monkeypatch.setattr(
        "backend.mllm.orchestration.compare_candidate_frames",
        lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("must not compare")),
    )
    monkeypatch.setattr(
        "backend.mllm.orchestration.generate_metadata",
        lambda *args, **kwargs: PhotoMetadataOutput(
            brief_description="탁자 위에 컵이 있어요.",
            long_description="탁자 위에 컵이 있어요.",
        ),
    )
    trigger_capture_pipeline("representative-only", revision, "")

    result = load_comparison_result("representative-only")
    state = load_capture_state("representative-only")
    assert result.improved is False
    assert state.status == "ready"
    assert state.final_frame_id == "representative"
    assert load_description("representative-only")["description"] == "탁자 위에 컵이 있어요."


def test_stale_pipeline_cannot_replace_newer_revision(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    first_revision = _begin_with_frames("reused", candidate=b"old-candidate")
    second_revision = {}

    def supersede_during_comparison(*args, **kwargs):
        second_revision["value"] = begin_capture_revision(
            "reused",
            lambda: save_representative_frame("reused", "frame.jpg", b"new-representative"),
        ).capture_revision
        return FrameComparisonResult(
            improved=True, selected_frame="candidate_1", reason="stale result"
        )

    monkeypatch.setattr(
        "backend.mllm.orchestration.compare_candidate_frames", supersede_during_comparison
    )
    monkeypatch.setattr(
        "backend.mllm.orchestration.generate_metadata",
        lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("stale description")),
    )
    trigger_capture_pipeline("reused", first_revision, "사진 찍어줘")

    representative, _ = load_session_frame_paths("reused")
    state = load_capture_state("reused")
    assert representative.read_bytes() == b"new-representative"
    assert state.capture_revision == second_revision["value"] == 2
    assert state.status == "uploaded"
    assert load_comparison_result("reused") is None
    assert load_description("reused") is None
    assert load_metadata("reused") is None
