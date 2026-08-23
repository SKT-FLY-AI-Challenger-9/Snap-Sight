# backend/storage/frame_buffer.py
"""Store capture frames below a validated, contained session directory."""

from __future__ import annotations

import os
import re
import shutil
import time
import uuid
from collections.abc import Collection
from pathlib import Path

from backend.storage.atomic import atomic_write_bytes

CAPTURES_DIR = Path("captures")
_SAFE_SESSION_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}\Z")
_SAFE_IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}


def validate_session_id(session_id: str) -> str:
    """Validate a path-safe id, optionally requiring canonical UUID form.

    Legacy Android builds use ``s_yyyyMMdd_HHmmss`` and remain accepted by
    default. Deployments can set ``SNAPSIGHT_REQUIRE_UUID_SESSION_IDS=1`` after
    all clients have migrated.
    """
    if not isinstance(session_id, str) or not _SAFE_SESSION_ID.fullmatch(session_id):
        raise ValueError(
            "session_id must be 1-64 ASCII letters, digits, '_' or '-' and may not contain paths"
        )
    if os.getenv("SNAPSIGHT_REQUIRE_UUID_SESSION_IDS", "").strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }:
        try:
            parsed = uuid.UUID(session_id)
        except ValueError as exc:
            raise ValueError("session_id must be a UUID in strict mode") from exc
        if str(parsed) != session_id:
            raise ValueError("session_id must use canonical UUID form in strict mode")
    return session_id


def session_dir_for(session_id: str) -> Path:
    """Return the contained session directory after validating its identifier."""
    safe_id = validate_session_id(session_id)
    root = CAPTURES_DIR.resolve()
    target = (root / safe_id).resolve()
    if not target.is_relative_to(root):  # defense in depth if validation changes later
        raise ValueError("session_id resolves outside the capture directory")
    return target


def session_exists(session_id: str) -> bool:
    """Return whether a capture session directory exists."""
    return session_dir_for(session_id).is_dir()


def save_representative_frame(session_id: str, filename: str, content: bytes) -> Path:
    """Store the representative image and return its path."""
    session_dir = _ensure_session_dir(session_id)
    path = session_dir / f"representative{_safe_image_suffix(filename)}"
    atomic_write_bytes(path, content)
    return path


def save_candidate_frame(session_id: str, index: int, filename: str, content: bytes) -> Path:
    """Store a candidate image under its zero-based numeric index."""
    if index < 0:
        raise ValueError("candidate index may not be negative")
    session_dir = _ensure_session_dir(session_id)
    path = session_dir / f"candidate_{index}{_safe_image_suffix(filename)}"
    atomic_write_bytes(path, content)
    return path


def load_session_frame_paths(session_id: str) -> tuple[Path, list[Path]]:
    """Return the representative and numerically sorted candidate paths."""
    session_dir = session_dir_for(session_id)
    representative_matches = list(session_dir.glob("representative.*"))
    if not representative_matches:
        raise FileNotFoundError(f"세션 {session_id}의 대표 컷을 찾을 수 없습니다: {session_dir}")
    candidate_matches = sorted(
        session_dir.glob("candidate_*"),
        key=lambda path: int(path.stem.split("_")[1]),
    )
    return representative_matches[0], candidate_matches


def cleanup_expired_sessions(
    ttl_seconds: int,
    *,
    now: float | None = None,
    protected_session_dirs: Collection[Path] = (),
    max_removals: int | None = None,
) -> int:
    """Remove a bounded set of expired direct child session directories.

    This is the low-level filesystem primitive. Runtime callers should use the
    capture-state wrapper so a cleanup pass cannot race an active revision.
    """
    if ttl_seconds <= 0:
        return 0
    if max_removals is not None and max_removals <= 0:
        return 0
    root = CAPTURES_DIR.resolve()
    if not root.exists():
        return 0
    cutoff = (time.time() if now is None else now) - ttl_seconds
    protected = {path.resolve() for path in protected_session_dirs}
    expired: list[tuple[float, Path]] = []
    for child in root.iterdir():
        try:
            if not child.is_dir() or child.is_symlink():
                continue
            resolved = child.resolve()
            modified_at = child.stat().st_mtime
        except FileNotFoundError:
            continue
        if resolved.parent != root or resolved in protected or modified_at >= cutoff:
            continue
        expired.append((modified_at, resolved))

    removed = 0
    for _, resolved in sorted(expired, key=lambda item: item[0]):
        if max_removals is not None and removed >= max_removals:
            break
        try:
            shutil.rmtree(resolved)
        except FileNotFoundError:
            continue
        removed += 1
    return removed


def clear_managed_capture_artifacts(session_id: str) -> None:
    """Delete only artifacts owned by an earlier revision of one safe session."""
    session_dir = _ensure_session_dir(session_id)
    managed_names = {
        "result.json",
        "description.json",
        "metadata.json",
        "understanding.json",
    }
    for path in session_dir.iterdir():
        if not path.is_file():
            continue
        if path.name in managed_names or path.name.startswith(("representative.", "candidate_")):
            path.unlink()


def _ensure_session_dir(session_id: str) -> Path:
    session_dir = session_dir_for(session_id)
    session_dir.mkdir(parents=True, exist_ok=True)
    return session_dir


def _safe_image_suffix(filename: str) -> str:
    suffix = Path(filename or "").suffix.lower()
    if suffix not in _SAFE_IMAGE_SUFFIXES:
        raise ValueError("capture image filename must end in .jpg, .jpeg, .png or .webp")
    return suffix
