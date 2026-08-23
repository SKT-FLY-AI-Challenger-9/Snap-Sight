"""Lifespan scheduling and active-revision safety for capture retention."""

import os
import threading
from io import BytesIO

from fastapi import FastAPI
from fastapi.testclient import TestClient
from PIL import Image

from backend.lifecycle import app_lifespan
from backend.storage.capture_state import (
    begin_capture_revision,
    cleanup_expired_capture_sessions,
    update_capture_state,
)
from backend.storage.frame_buffer import save_representative_frame


def _make_jpeg() -> bytes:
    output = BytesIO()
    Image.new("RGB", (2, 2), "white").save(output, format="JPEG")
    return output.getvalue()


DUMMY_JPEG = _make_jpeg()


def test_cleanup_is_bounded_and_removes_oldest_sessions_first(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    for name, modified_at in (("oldest", 10), ("older", 20), ("old", 30)):
        session_dir = tmp_path / "captures" / name
        session_dir.mkdir(parents=True)
        os.utime(session_dir, (modified_at, modified_at))

    removed = cleanup_expired_capture_sessions(10, now=100, max_removals=2)

    assert removed == 2
    assert not (tmp_path / "captures" / "oldest").exists()
    assert not (tmp_path / "captures" / "older").exists()
    assert (tmp_path / "captures" / "old").exists()


def test_short_ttl_never_deletes_an_active_revision(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    state = begin_capture_revision(
        "active-short-ttl",
        lambda: save_representative_frame("active-short-ttl", "frame.jpg", DUMMY_JPEG),
    )
    session_dir = tmp_path / "captures" / "active-short-ttl"
    os.utime(session_dir, (10, 10))

    assert cleanup_expired_capture_sessions(1, now=100, max_removals=10) == 0
    assert session_dir.is_dir()

    update_capture_state("active-short-ttl", state.capture_revision, status="failed")
    os.utime(session_dir, (10, 10))
    assert cleanup_expired_capture_sessions(1, now=100, max_removals=10) == 1
    assert not session_dir.exists()


def test_lifespan_skips_cleanup_task_when_ttl_is_disabled(monkeypatch):
    monkeypatch.setenv("SNAPSIGHT_CAPTURE_TTL_SECONDS", "0")
    app = FastAPI(lifespan=app_lifespan)

    with TestClient(app):
        assert app.state.capture_cleanup_task is None

    assert app.state.capture_cleanup_task is None


def test_lifespan_immediately_cleans_an_expired_terminal_session(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    state = begin_capture_revision(
        "expired-at-startup",
        lambda: save_representative_frame("expired-at-startup", "frame.jpg", DUMMY_JPEG),
    )
    update_capture_state("expired-at-startup", state.capture_revision, status="ready")
    session_dir = tmp_path / "captures" / "expired-at-startup"
    os.utime(session_dir, (10, 10))
    monkeypatch.setenv("SNAPSIGHT_CAPTURE_TTL_SECONDS", "1")
    monkeypatch.setenv("SNAPSIGHT_CAPTURE_CLEANUP_INTERVAL_SECONDS", "3600")
    app = FastAPI(lifespan=app_lifespan)

    with TestClient(app):
        assert not session_dir.exists()


def test_lifespan_runs_immediately_then_periodically_and_cancels(monkeypatch):
    calls: list[tuple[int, int | None]] = []
    periodic_call = threading.Event()

    def fake_cleanup(ttl_seconds: int, *, max_removals: int | None = None) -> int:
        calls.append((ttl_seconds, max_removals))
        if len(calls) >= 2:
            periodic_call.set()
        return 0

    monkeypatch.setattr(
        "backend.lifecycle.cleanup_expired_capture_sessions",
        fake_cleanup,
    )
    monkeypatch.setattr("backend.lifecycle.load_capture_ttl_seconds", lambda: 10)
    monkeypatch.setattr(
        "backend.lifecycle.load_capture_cleanup_interval_seconds",
        lambda: 0.01,
    )
    monkeypatch.setattr("backend.lifecycle.load_capture_cleanup_batch_size", lambda: 3)
    app = FastAPI(lifespan=app_lifespan)

    with TestClient(app):
        cleanup_task = app.state.capture_cleanup_task
        assert cleanup_task is not None
        assert periodic_call.wait(timeout=1)
        assert not cleanup_task.done()

    assert cleanup_task.done()
    assert app.state.capture_cleanup_task is None
    assert calls[0] == (10, 3)
    assert len(calls) >= 2
