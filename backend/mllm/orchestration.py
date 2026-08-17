# backend/mllm/orchestration.py
"""촬영 저장 완료 후 MLLM 후보 비교를 비동기로 실행하고, 개선된 후보가 있으면 대표 컷을 교체한다."""

from pathlib import Path

from backend.mllm.client import compare_candidate_frames
from backend.mllm.prompts import FrameComparisonResult
from backend.mllm.target_requirements import build_requirements_from_text
from backend.storage.comparison_result import save_comparison_result
from backend.storage.frame_buffer import load_session_frame_paths
from backend.utils.logger import load_logger

logger = load_logger("mllm_orchestration.log")


def trigger_comparison(
    session_id: str,
    raw_text: str,
    candidate_scores: list[dict] | None = None,
) -> None:
    """세션의 저장된 프레임으로 MLLM 비교를 실행하고, 결과를 저장·반영한다.

    BackgroundTasks에서 실행되므로 예외를 절대 밖으로 흘리지 않는다 — 예외로 중단되면
    결과가 저장되지 않아 조회 API가 영원히 pending을 반환하게 된다.
    """
    try:
        result = _run_comparison(session_id, raw_text, candidate_scores)
    except Exception as exc:  # noqa: BLE001 - 어떤 실패든 대표 컷 유지 결과로 수렴시켜야 한다
        logger.error(f"세션 {session_id}: MLLM 비교 중 예외 발생 — {exc}")
        result = FrameComparisonResult(
            improved=False,
            selected_frame=None,
            reason=f"MLLM 비교 실패로 대표 컷 유지: {exc}",
        )

    save_comparison_result(session_id, result)
    logger.info(f"세션 {session_id}: MLLM 비교 완료 — improved={result.improved}")


def _run_comparison(
    session_id: str,
    raw_text: str,
    candidate_scores: list[dict] | None,
) -> FrameComparisonResult:
    """MLLM 비교를 실행하고, 대표 컷 교체까지 마친 뒤 실제 반영 결과를 반환한다."""
    representative, candidates = load_session_frame_paths(session_id)
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

    if _replace_representative_frame(session_id, representative, candidates, result):
        return result

    # 교체하지 못했는데 improved=True로 저장하면 조회 API가 앱에 "교체됨"이라고 거짓 보고하게 된다.
    return FrameComparisonResult(
        improved=False,
        selected_frame=None,
        reason=f"MLLM이 {result.selected_frame}을 선택했으나 교체에 실패해 대표 컷을 유지함",
    )


def _replace_representative_frame(
    session_id: str,
    representative: Path,
    candidates: list[Path],
    result: FrameComparisonResult,
) -> bool:
    """선택된 후보 프레임 내용으로 대표 컷 파일을 교체하고, 교체했으면 True를 반환한다."""
    index = _selected_candidate_index(result.selected_frame)
    if index is None or not 0 <= index < len(candidates):
        logger.error(
            f"세션 {session_id}: selected_frame '{result.selected_frame}'이 "
            f"후보 범위(0~{len(candidates) - 1})를 벗어나 교체를 건너뜀"
        )
        return False

    selected = candidates[index]
    target = representative.with_suffix(selected.suffix)
    target.write_bytes(selected.read_bytes())
    if target != representative:
        # 확장자가 다르면 이전 대표 컷 파일이 남아 load_session_frame_paths가 둘 중
        # 아무거나 고르게 되고, media_type도 잘못 붙는다.
        representative.unlink()
    return True


def _selected_candidate_index(selected_frame: str | None) -> int | None:
    """'candidate_3' 형식의 식별자에서 0-based 후보 인덱스를 추출한다."""
    if selected_frame is None or not selected_frame.startswith("candidate_"):
        return None
    try:
        return int(selected_frame.removeprefix("candidate_")) - 1
    except ValueError:
        return None
