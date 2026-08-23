"""Optional API authentication and lightweight per-client rate limiting."""

from __future__ import annotations

import secrets
import threading
import time
from collections import defaultdict, deque

from fastapi import Header, HTTPException, Request, status

from backend.config import load_api_token, load_max_request_bytes, load_rate_limit_per_minute

_rate_lock = threading.Lock()
_request_times: dict[str, deque[float]] = defaultdict(deque)


def require_api_access(
    request: Request,
    x_snap_sight_token: str | None = Header(default=None),
    authorization: str | None = Header(default=None),
) -> None:
    """Apply configured token auth and rate limits.

    ``SNAPSIGHT_API_TOKEN`` and ``SNAPSIGHT_RATE_LIMIT_PER_MINUTE`` are both
    disabled by default.  A token may be sent as ``X-Snap-Sight-Token`` or a
    standard Bearer authorization value.
    """
    content_length = request.headers.get("content-length")
    if content_length is not None:
        try:
            declared_length = int(content_length)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail="invalid Content-Length header") from exc
        if declared_length < 0:
            raise HTTPException(status_code=400, detail="invalid Content-Length header")
        if declared_length > load_max_request_bytes():
            raise HTTPException(status_code=413, detail="request body is too large")

    expected = load_api_token()
    if expected is not None:
        bearer = None
        if authorization and authorization.lower().startswith("bearer "):
            bearer = authorization[7:].strip()
        supplied = x_snap_sight_token or bearer or ""
        if not secrets.compare_digest(supplied, expected):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="valid Snap-Sight API token required",
                headers={"WWW-Authenticate": "Bearer"},
            )

    limit = load_rate_limit_per_minute()
    if limit <= 0:
        return
    client_key = request.client.host if request.client is not None else "unknown"
    now = time.monotonic()
    with _rate_lock:
        timestamps = _request_times[client_key]
        while timestamps and timestamps[0] <= now - 60:
            timestamps.popleft()
        if len(timestamps) >= limit:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail="request rate limit exceeded",
                headers={"Retry-After": "60"},
            )
        timestamps.append(now)


def reset_rate_limit_state() -> None:
    """Clear process-local counters (primarily useful for tests)."""
    with _rate_lock:
        _request_times.clear()
