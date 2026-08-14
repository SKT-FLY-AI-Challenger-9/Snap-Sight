"""Tracker interface independent from detector and visualization code."""

from __future__ import annotations

from collections.abc import Sequence
from typing import Protocol

from ai.on_device_cv.contracts import DetectionResult, TrackedObject


class Tracker(Protocol):
    """Stateful association contract for one continuous video stream."""

    def update(
        self,
        detections: Sequence[DetectionResult],
        *,
        timestamp_s: float | None = None,
    ) -> Sequence[TrackedObject]:
        """Associate the current observations and return observed tracks."""

    def reset(self) -> None:
        """Discard all tracks and restart ID allocation."""
