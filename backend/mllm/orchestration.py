"""Select one canonical frame, then derive all descriptions from that image."""

from __future__ import annotations

import time
from pathlib import Path

from ai.photo_labels import default_photo_labels
from backend.mllm.client import compare_candidate_frames
from backend.mllm.description import save_description
from backend.mllm.metadata import MODEL_ID as METADATA_MODEL_ID
from backend.mllm.metadata import generate_metadata, save_metadata
from backend.mllm.prompts import FrameComparisonResult
from backend.mllm.target_requirements import build_requirements_from_text
from backend.storage.atomic import atomic_write_bytes
from backend.storage.capture_state import (
    is_current_revision,
    run_if_current_revision,
    update_capture_state,
)
from backend.storage.comparison_result import save_comparison_result
from backend.storage.frame_buffer import load_session_frame_paths
from backend.utils.logger import load_logger

logger = load_logger("mllm_orchestration.log")


def trigger_capture_pipeline(
    session_id: str,
    capture_revision: int,
    raw_text: str,
    candidate_scores: list[dict] | None = None,
    custom_labels: list[str] | None = None,
    detected_objects: list[str] | None = None,
    known_subjects: list[dict] | None = None,
) -> None:
    """Finish one capture revision in a deterministic two-stage order.

    Candidate selection (and any representative replacement) completes first.
    One structured model call then creates both the brief and detailed outputs
    from that exact canonical image. Revision-guarded commits prevent an older
    worker from overwriting a newer upload that reused the same session id.
    """
    if update_capture_state(session_id, capture_revision, status="selecting") is None:
        return

    _t_selecting = time.perf_counter()
    try:
        comparison = _run_comparison(
            session_id,
            raw_text,
            candidate_scores,
            expected_revision=capture_revision,
        )
    except Exception as exc:  # noqa: BLE001 - polling must reach a terminal result
        logger.error(f"Session {session_id}: frame comparison failed: {exc}")
        comparison = FrameComparisonResult(
            improved=False,
            selected_frame=None,
            reason=f"Frame comparison failed; kept representative: {exc}",
        )
    logger.info(
        f"Session {session_id}: frame comparison stage took "
        f"{(time.perf_counter() - _t_selecting) * 1000:.0f}ms (improved={comparison.improved})"
    )

    if not is_current_revision(session_id, capture_revision):
        return
    final_frame_id = comparison.selected_frame if comparison.improved else "representative"

    def _commit_selection() -> None:
        save_comparison_result(session_id, comparison)
        update_capture_state(
            session_id,
            capture_revision,
            status="describing",
            final_frame_id=final_frame_id,
        )

    try:
        committed, _ = run_if_current_revision(session_id, capture_revision, _commit_selection)
    except Exception as exc:  # noqa: BLE001 - convert storage failures to terminal state
        _mark_pipeline_failed(session_id, capture_revision, "selection commit", exc)
        return
    if not committed:
        return

    try:
        taxonomy = default_photo_labels()
    except Exception as exc:  # noqa: BLE001 - taxonomy is required for a valid output
        _mark_pipeline_failed(session_id, capture_revision, "taxonomy load", exc)
        return
    _t_describing = time.perf_counter()
    try:
        representative, _ = load_session_frame_paths(session_id)
        understanding = generate_metadata(
            representative,
            raw_text=raw_text,
            custom_labels=custom_labels or [],
            detected_objects=detected_objects or [],
            known_subjects=known_subjects or [],
            taxonomy=taxonomy,
        )
    except Exception as exc:  # noqa: BLE001 - save explicit null output on any model failure
        logger.error(f"Session {session_id}: canonical image understanding failed: {exc}")
        understanding = None
    logger.info(
        f"Session {session_id}: description generation stage took "
        f"{(time.perf_counter() - _t_describing) * 1000:.0f}ms (model={METADATA_MODEL_ID})"
    )

    def _commit_understanding() -> None:
        save_description(
            session_id,
            understanding.brief() if understanding is not None else None,
            capture_revision=capture_revision,
            final_frame_id=final_frame_id,
        )
        save_metadata(
            session_id,
            understanding,
            taxonomy,
            custom_labels or [],
            capture_revision=capture_revision,
            final_frame_id=final_frame_id,
        )
        update_capture_state(
            session_id,
            capture_revision,
            status="ready",
            final_frame_id=final_frame_id,
        )

    try:
        committed, _ = run_if_current_revision(session_id, capture_revision, _commit_understanding)
    except Exception as exc:  # noqa: BLE001 - convert storage failures to terminal state
        _mark_pipeline_failed(session_id, capture_revision, "understanding commit", exc)
        return
    if committed:
        logger.info(
            f"Session {session_id} revision {capture_revision}: capture pipeline ready "
            f"(final_frame_id={final_frame_id})"
        )


def trigger_comparison(
    session_id: str,
    raw_text: str,
    candidate_scores: list[dict] | None = None,
) -> None:
    """Legacy comparison-only entry point retained for existing callers/tests."""
    try:
        result = _run_comparison(session_id, raw_text, candidate_scores)
    except Exception as exc:  # noqa: BLE001 - legacy polling must reach a terminal result
        logger.error(f"Session {session_id}: frame comparison failed: {exc}")
        result = FrameComparisonResult(
            improved=False,
            selected_frame=None,
            reason=f"Frame comparison failed; kept representative: {exc}",
        )

    save_comparison_result(session_id, result)
    logger.info(f"Session {session_id}: frame comparison complete (improved={result.improved})")


def _run_comparison(
    session_id: str,
    raw_text: str,
    candidate_scores: list[dict] | None,
    *,
    expected_revision: int | None = None,
) -> FrameComparisonResult:
    representative, candidates = load_session_frame_paths(session_id)
    if not candidates:
        return FrameComparisonResult(
            improved=False,
            selected_frame=None,
            reason="No candidate frames were uploaded; kept representative.",
        )

    structured_requirements = build_requirements_from_text(session_id, raw_text)
    result = compare_candidate_frames(
        raw_text,
        structured_requirements,
        representative,
        candidates,
        candidate_scores=candidate_scores,
    )
    if not result.improved:
        return result

    if _replace_representative_frame(
        session_id,
        representative,
        candidates,
        result,
        expected_revision=expected_revision,
    ):
        return result

    return FrameComparisonResult(
        improved=False,
        selected_frame=None,
        reason=f"Selected {result.selected_frame}, but replacement failed; kept representative.",
    )


def _replace_representative_frame(
    session_id: str,
    representative: Path,
    candidates: list[Path],
    result: FrameComparisonResult,
    *,
    expected_revision: int | None = None,
) -> bool:
    index = _selected_candidate_index(result.selected_frame)
    if index is None or not 0 <= index < len(candidates):
        logger.error(
            f"Session {session_id}: selected frame {result.selected_frame!r} is outside "
            f"candidate range 1..{len(candidates)}"
        )
        return False

    selected = candidates[index]

    def _replace() -> bool:
        target = representative.with_suffix(selected.suffix)
        atomic_write_bytes(target, selected.read_bytes())
        if target != representative and representative.exists():
            representative.unlink()
        return True

    if expected_revision is None:
        return _replace()
    committed, replaced = run_if_current_revision(session_id, expected_revision, _replace)
    return bool(committed and replaced)


def _selected_candidate_index(selected_frame: str | None) -> int | None:
    """Convert the model's one-based ``candidate_N`` identifier to an index."""
    if selected_frame is None or not selected_frame.startswith("candidate_"):
        return None
    try:
        return int(selected_frame.removeprefix("candidate_")) - 1
    except ValueError:
        return None


def _mark_pipeline_failed(
    session_id: str, capture_revision: int, stage: str, exc: Exception
) -> None:
    logger.error(
        f"Session {session_id} revision {capture_revision}: {stage} failed: {exc}"
    )
    try:
        update_capture_state(session_id, capture_revision, status="failed")
    except Exception as state_exc:  # noqa: BLE001 - original storage failure is already terminal
        logger.error(
            f"Session {session_id} revision {capture_revision}: failed to persist failure state: "
            f"{state_exc}"
        )
