# backend/storage/frame_buffer.py
"""촬영 세션별 대표 컷·후보 프레임을 로컬 파일 시스템(captures/{session_id}/)에 저장한다."""

from pathlib import Path

CAPTURES_DIR = Path("captures")


def save_representative_frame(session_id: str, filename: str, content: bytes) -> Path:
    """대표 컷 이미지를 세션 디렉터리에 저장하고 저장된 경로를 반환한다."""
    session_dir = _ensure_session_dir(session_id)
    path = session_dir / f"representative{Path(filename).suffix}"
    path.write_bytes(content)
    return path


def save_candidate_frame(session_id: str, index: int, filename: str, content: bytes) -> Path:
    """후보 프레임 이미지를 인덱스 기반 파일명으로 세션 디렉터리에 저장하고 저장된 경로를 반환한다."""
    session_dir = _ensure_session_dir(session_id)
    path = session_dir / f"candidate_{index}{Path(filename).suffix}"
    path.write_bytes(content)
    return path


def _ensure_session_dir(session_id: str) -> Path:
    """세션 디렉터리가 없으면 생성하고 경로를 반환한다."""
    session_dir = CAPTURES_DIR / session_id
    session_dir.mkdir(parents=True, exist_ok=True)
    return session_dir
