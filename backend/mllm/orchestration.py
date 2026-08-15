# backend/mllm/orchestration.py
"""촬영 저장 완료 후 MLLM 후보 비교를 비동기로 실행하고, 개선된 후보가 있으면 대표 컷을 교체한다."""

from pathlib import Path

from backend.mllm.client import compare_candidate_frames
from backend.mllm.prompts import FrameComparisonResult
from backend.storage.comparison_result import save_comparison_result
from backend.storage.frame_buffer import load_session_frame_paths
from backend.utils.logger import load_logger

logger = load_logger("mllm_orchestration.log")


def trigger_comparison(
    session_id: str,
    raw_text: str,
    candidate_scores: list[dict] | None = None,
) -> None:
    """세션의 저장된 프레임으로 MLLM 비교를 실행하고, 결과를 저장·반영한다."""
    representative, candidates = load_session_frame_paths(session_id)
    result = compare_candidate_frames(
        raw_text, {}, representative, candidates, candidate_scores=candidate_scores
    )
    save_comparison_result(session_id, result)

    if result.improved:
        _replace_representative_frame(session_id, representative, candidates, result)

    logger.info(f"세션 {session_id}: MLLM 비교 완료 — improved={result.improved}")


def _replace_representative_frame(
    session_id: str,
    representative: Path,
    candidates: list[Path],
    result: FrameComparisonResult,
) -> None:
    """선택된 후보 프레임 내용으로 대표 컷 파일을 교체한다."""
    index = _selected_candidate_index(result.selected_frame)
    if index is None or not 0 <= index < len(candidates):
        logger.error(
            f"세션 {session_id}: selected_frame '{result.selected_frame}'이 "
            f"후보 범위(0~{len(candidates) - 1})를 벗어나 교체를 건너뜀"
        )
        return
    representative.write_bytes(candidates[index].read_bytes())


def _selected_candidate_index(selected_frame: str | None) -> int | None:
    """'candidate_3' 형식의 식별자에서 0-based 후보 인덱스를 추출한다."""
    if selected_frame is None or not selected_frame.startswith("candidate_"):
        return None
    try:
        return int(selected_frame.removeprefix("candidate_")) - 1
    except ValueError:
        return None
