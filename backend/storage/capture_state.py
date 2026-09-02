"""Monotonic per-session capture revision and canonical-frame state."""

from __future__ import annotations

import json
import threading
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal, TypeVar

from pydantic import BaseModel, Field

from backend.storage.atomic import atomic_write_text
from backend.storage.frame_buffer import (
    cleanup_expired_sessions,
    clear_managed_capture_artifacts,
    session_dir_for,
)

STATE_FILENAME = "capture_state.json"
CaptureStatus = Literal["uploaded", "selecting", "describing", "ready", "failed"]


class CaptureState(BaseModel):
    schema_version: int = 1
    capture_revision: int = Field(ge=1)
    status: CaptureStatus = "uploaded"
    final_frame_id: str | None = None
    updated_at: str


_state_lock = threading.RLock()
_active_revisions: dict[Path, int] = {}
_T = TypeVar("_T")


def begin_capture_revision(
    session_id: str, initialize: Callable[[], object] | None = None
) -> CaptureState:
    """Increment a revision and optionally initialize its files under one lock."""
    with _state_lock:
        session_path = session_dir_for(session_id)
        previous = _load_unlocked(session_id)
        revision = 1 if previous is None else previous.capture_revision + 1
        clear_managed_capture_artifacts(session_id)
        state = CaptureState(capture_revision=revision, updated_at=_now())
        _save_unlocked(session_id, state)
        _active_revisions[session_path] = revision
        if initialize is not None:
            try:
                initialize()
            except Exception:
                failed = state.model_copy(update={"status": "failed", "updated_at": _now()})
                _save_unlocked(session_id, failed)
                _active_revisions.pop(session_path, None)
                raise
        return state


def load_capture_state(session_id: str) -> CaptureState | None:
    with _state_lock:
        return _load_unlocked(session_id)


def read_capture_snapshot(
    session_id: str, reader: Callable[[], _T]
) -> tuple[CaptureState | None, _T]:
    """Read state and a related artifact without a revision changing between them."""
    with _state_lock:
        return _load_unlocked(session_id), reader()


def is_current_revision(session_id: str, capture_revision: int) -> bool:
    state = load_capture_state(session_id)
    return state is not None and state.capture_revision == capture_revision


def update_capture_state(
    session_id: str,
    capture_revision: int,
    *,
    status: CaptureStatus | None = None,
    final_frame_id: str | None = None,
) -> CaptureState | None:
    """Update only if *capture_revision* is still current; stale workers get None."""
    with _state_lock:
        current = _load_unlocked(session_id)
        if current is None or current.capture_revision != capture_revision:
            return None
        updates: dict[str, object] = {"updated_at": _now()}
        if status is not None:
            updates["status"] = status
        if final_frame_id is not None:
            updates["final_frame_id"] = final_frame_id
        updated = current.model_copy(update=updates)
        _save_unlocked(session_id, updated)
        if status in {"ready", "failed"}:
            session_path = session_dir_for(session_id)
            if _active_revisions.get(session_path) == capture_revision:
                _active_revisions.pop(session_path, None)
        return updated


def run_if_current_revision(
    session_id: str, capture_revision: int, action: Callable[[], _T]
) -> tuple[bool, _T | None]:
    """Run a filesystem commit atomically with respect to revision changes."""
    with _state_lock:
        current = _load_unlocked(session_id)
        if current is None or current.capture_revision != capture_revision:
            return False, None
        return True, action()


def cleanup_expired_capture_sessions(
    ttl_seconds: int,
    *,
    now: float | None = None,
    max_removals: int | None = None,
) -> int:
    """Remove expired sessions without racing revisions active in this process.

    The state lock covers both the active-revision snapshot and the filesystem
    pass. Even a very short TTL therefore cannot remove frames while upload,
    selection, understanding, polling commits, or final-frame reads hold the
    same lock. A terminal state releases its process-local protection.
    """
    with _state_lock:
        return cleanup_expired_sessions(
            ttl_seconds,
            now=now,
            protected_session_dirs=tuple(_active_revisions),
            max_removals=max_removals,
        )


def _load_unlocked(session_id: str) -> CaptureState | None:
    path = session_dir_for(session_id) / STATE_FILENAME
    if not path.exists():
        return None
    return CaptureState.model_validate_json(path.read_text(encoding="utf-8"))


def _save_unlocked(session_id: str, state: CaptureState) -> None:
    path = session_dir_for(session_id) / STATE_FILENAME
    atomic_write_text(path, json.dumps(state.model_dump(), ensure_ascii=False))


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
