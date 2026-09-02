# backend/storage/comparison_result.py
"""MLLM 후보 비교 결과를 세션 디렉터리에 저장하고 조회한다."""

from pathlib import Path

from backend.mllm.prompts import FrameComparisonResult
from backend.storage.atomic import atomic_write_text
from backend.storage.frame_buffer import session_dir_for

RESULT_FILENAME = "result.json"


def save_comparison_result(session_id: str, result: FrameComparisonResult) -> Path:
    """비교 결과를 세션 디렉터리에 JSON으로 저장하고 저장된 경로를 반환한다."""
    session_dir = session_dir_for(session_id)
    session_dir.mkdir(parents=True, exist_ok=True)
    path = session_dir / RESULT_FILENAME
    atomic_write_text(path, result.model_dump_json())
    return path


def load_comparison_result(session_id: str) -> FrameComparisonResult | None:
    """저장된 비교 결과를 읽어 반환한다. 아직 없으면 None을 반환한다."""
    path = session_dir_for(session_id) / RESULT_FILENAME
    if not path.exists():
        return None
    return FrameComparisonResult.model_validate_json(path.read_text(encoding="utf-8"))
