"""FastAPI lifespan services for bounded capture retention cleanup."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI

from backend.config import (
    load_capture_cleanup_batch_size,
    load_capture_cleanup_interval_seconds,
    load_capture_ttl_seconds,
)
from backend.storage.capture_state import cleanup_expired_capture_sessions
from backend.utils.logger import load_logger

logger = load_logger("capture_cleanup.log")


@asynccontextmanager
async def app_lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Run capture cleanup immediately and periodically while the app is alive."""
    ttl_seconds = load_capture_ttl_seconds()
    app.state.capture_cleanup_task = None
    if ttl_seconds <= 0:
        yield
        return

    interval_seconds = load_capture_cleanup_interval_seconds()
    batch_size = load_capture_cleanup_batch_size()
    await _run_cleanup_pass(ttl_seconds, batch_size)
    cleanup_task = asyncio.create_task(
        _periodic_cleanup(ttl_seconds, interval_seconds, batch_size),
        name="snap-sight-capture-cleanup",
    )
    app.state.capture_cleanup_task = cleanup_task
    try:
        yield
    finally:
        cleanup_task.cancel()
        with suppress(asyncio.CancelledError):
            await cleanup_task
        app.state.capture_cleanup_task = None


async def _periodic_cleanup(
    ttl_seconds: int, interval_seconds: float, batch_size: int
) -> None:
    while True:
        await asyncio.sleep(interval_seconds)
        await _run_cleanup_pass(ttl_seconds, batch_size)


async def _run_cleanup_pass(ttl_seconds: int, batch_size: int) -> int:
    """Run one bounded pass off-loop and finish it safely if cancellation arrives."""
    worker = asyncio.create_task(
        asyncio.to_thread(
            cleanup_expired_capture_sessions,
            ttl_seconds,
            max_removals=batch_size,
        )
    )
    try:
        removed = await asyncio.shield(worker)
    except asyncio.CancelledError:
        # Cancelling asyncio.to_thread cannot stop its worker. Await the bounded,
        # state-locked pass before propagating cancellation so shutdown cannot
        # leave an unobserved deletion running in the executor.
        with suppress(Exception):
            await worker
        raise
    except Exception:
        logger.exception("Capture retention cleanup failed")
        return 0

    if removed:
        logger.info(f"Removed {removed} expired capture session(s)")
    return removed
