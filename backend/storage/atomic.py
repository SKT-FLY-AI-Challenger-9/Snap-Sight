"""Small atomic file-write helpers used by capture artifacts."""

from __future__ import annotations

import os
import uuid
from pathlib import Path


def atomic_write_bytes(path: Path, content: bytes) -> None:
    """Write beside *path* and atomically replace the destination."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_bytes(content)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def atomic_write_text(path: Path, content: str, *, encoding: str = "utf-8") -> None:
    """Text counterpart of :func:`atomic_write_bytes`."""
    atomic_write_bytes(path, content.encode(encoding))
