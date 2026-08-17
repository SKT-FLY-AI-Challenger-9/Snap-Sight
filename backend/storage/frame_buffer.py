# backend/storage/frame_buffer.py
"""촬영 세션별 대표 컷·후보 프레임을 로컬 파일 시스템(captures/{session_id}/)에 저장한다."""

from pathlib import Path

CAPTURES_DIR = Path("captures")


def session_exists(session_id: str) -> bool:
    """업로드된 적이 있는 세션인지 확인한다. 결과 조회에서 '없는 세션'과 '대기 중'을 구분하는 데 쓴다."""
    return (CAPTURES_DIR / session_id).is_dir()


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


def load_session_frame_paths(session_id: str) -> tuple[Path, list[Path]]:
    """세션 디렉터리에 저장된 대표 컷 경로와 후보 프레임 경로 목록(인덱스 순)을 읽어 반환한다."""
    session_dir = CAPTURES_DIR / session_id
    representative_matches = list(session_dir.glob("representative.*"))
    if not representative_matches:
        raise FileNotFoundError(f"세션 {session_id}의 대표 컷을 찾을 수 없습니다: {session_dir}")
    candidate_matches = sorted(
        session_dir.glob("candidate_*"),
        key=lambda path: int(path.stem.split("_")[1]),
    )
    return representative_matches[0], candidate_matches


def _ensure_session_dir(session_id: str) -> Path:
    """세션 디렉터리가 없으면 생성하고 경로를 반환한다."""
    session_dir = CAPTURES_DIR / session_id
    session_dir.mkdir(parents=True, exist_ok=True)
    return session_dir
