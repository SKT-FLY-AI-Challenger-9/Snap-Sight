"""Repository-level environment and server configuration."""

from __future__ import annotations

import os

from dotenv import load_dotenv

from backend.utils.logger import load_logger

logger = load_logger("config.log")
load_dotenv()

REQUIRED_ENV_VARS: tuple[str, ...] = ("ANTHROPIC_API_KEY",)

DEFAULT_SERVER_HOST = "0.0.0.0"
DEFAULT_SERVER_PORT = 8000
RESULT_POLL_INTERVAL_SECONDS = 2
RESULT_POLL_TIMEOUT_SECONDS = 30

# Auth and rate limiting remain opt-in for local development. Capture cleanup and
# upload resource limits are safe by default so uploaded photos are not retained forever.
DEFAULT_MAX_CAPTURE_FILE_BYTES = 16 * 1024 * 1024
DEFAULT_MAX_CAPTURE_TOTAL_BYTES = 96 * 1024 * 1024
DEFAULT_MAX_REQUEST_BYTES = 100 * 1024 * 1024
DEFAULT_MAX_CANDIDATE_FRAMES = 12
DEFAULT_CAPTURE_TTL_SECONDS = 24 * 60 * 60
DEFAULT_CAPTURE_CLEANUP_INTERVAL_SECONDS = 15 * 60
DEFAULT_CAPTURE_CLEANUP_BATCH_SIZE = 64
MAX_CAPTURE_CLEANUP_INTERVAL_SECONDS = 24 * 60 * 60
MAX_CAPTURE_CLEANUP_BATCH_SIZE = 1024


def load_env_variable(key: str) -> str:
    value = os.getenv(key)
    if not value:
        raise RuntimeError(
            f"Required environment variable {key!r} is missing. "
            f"Add {key}=... to the repository .env file."
        )
    return value


def validate_required_env() -> None:
    for key in REQUIRED_ENV_VARS:
        load_env_variable(key)
    logger.info("Required environment variables are configured")


def load_server_host() -> str:
    return os.getenv("SERVER_HOST") or DEFAULT_SERVER_HOST


def load_server_port() -> int:
    raw_port = os.getenv("SERVER_PORT")
    if not raw_port:
        return DEFAULT_SERVER_PORT
    try:
        return int(raw_port)
    except ValueError:
        logger.error(
            f"SERVER_PORT {raw_port!r} is not an integer; using {DEFAULT_SERVER_PORT}"
        )
        return DEFAULT_SERVER_PORT


def load_max_capture_file_bytes() -> int:
    return _load_bounded_int(
        "SNAPSIGHT_MAX_CAPTURE_FILE_BYTES", DEFAULT_MAX_CAPTURE_FILE_BYTES, minimum=1
    )


def load_max_capture_total_bytes() -> int:
    return _load_bounded_int(
        "SNAPSIGHT_MAX_CAPTURE_TOTAL_BYTES", DEFAULT_MAX_CAPTURE_TOTAL_BYTES, minimum=1
    )


def load_max_request_bytes() -> int:
    return _load_bounded_int(
        "SNAPSIGHT_MAX_REQUEST_BYTES", DEFAULT_MAX_REQUEST_BYTES, minimum=1
    )


def load_max_candidate_frames() -> int:
    return _load_bounded_int(
        "SNAPSIGHT_MAX_CANDIDATE_FRAMES", DEFAULT_MAX_CANDIDATE_FRAMES, minimum=0
    )


def load_capture_ttl_seconds() -> int:
    """Return the capture retention window; explicitly set zero only to disable cleanup."""
    return _load_bounded_int(
        "SNAPSIGHT_CAPTURE_TTL_SECONDS", DEFAULT_CAPTURE_TTL_SECONDS, minimum=0
    )


def load_capture_cleanup_interval_seconds() -> int:
    """Return the interval between bounded capture cleanup passes."""
    return _load_bounded_int(
        "SNAPSIGHT_CAPTURE_CLEANUP_INTERVAL_SECONDS",
        DEFAULT_CAPTURE_CLEANUP_INTERVAL_SECONDS,
        minimum=1,
        maximum=MAX_CAPTURE_CLEANUP_INTERVAL_SECONDS,
    )


def load_capture_cleanup_batch_size() -> int:
    """Return the maximum number of expired sessions removed in one pass."""
    return _load_bounded_int(
        "SNAPSIGHT_CAPTURE_CLEANUP_BATCH_SIZE",
        DEFAULT_CAPTURE_CLEANUP_BATCH_SIZE,
        minimum=1,
        maximum=MAX_CAPTURE_CLEANUP_BATCH_SIZE,
    )


def load_api_token() -> str | None:
    """Return the optional shared API token; unset keeps local development open."""
    value = os.getenv("SNAPSIGHT_API_TOKEN", "").strip()
    return value or None


def load_rate_limit_per_minute() -> int:
    """Return zero when the process-local request limit is disabled."""
    return _load_bounded_int("SNAPSIGHT_RATE_LIMIT_PER_MINUTE", 0, minimum=0)


def _load_bounded_int(
    key: str, default: int, *, minimum: int, maximum: int | None = None
) -> int:
    raw = os.getenv(key)
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw)
    except ValueError:
        logger.error(f"{key}={raw!r} is not an integer; using {default}")
        return default
    if value < minimum:
        logger.error(f"{key}={value} is below {minimum}; using {default}")
        return default
    if maximum is not None and value > maximum:
        logger.error(f"{key}={value} is above {maximum}; using {default}")
        return default
    return value
