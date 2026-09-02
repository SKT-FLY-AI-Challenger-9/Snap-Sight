"""Optional detection stages inserted before tracking."""

from __future__ import annotations

from collections.abc import Sequence
from typing import Protocol

import numpy as np

from ai.on_device_cv.contracts import DetectionResult


class DetectionExtension(Protocol):
    """Add common detections without coupling the pipeline to a model runtime.

    A future face extension can select ``person`` detections, run a face detector
    on each crop, remap crop boxes to full-frame normalized coordinates, and
    return those faces here.
    """

    def load(self) -> None:
        """Load optional resources."""

    def extend(
        self,
        frame_bgr: np.ndarray,
        primary_detections: Sequence[DetectionResult],
    ) -> Sequence[DetectionResult]:
        """Return additional post-processed detections for this frame."""

    def close(self) -> None:
        """Release optional resources."""


class NoOpDetectionExtension:
    """Useful explicit placeholder for deployments without extra detectors."""

    def load(self) -> None:
        pass

    def extend(
        self,
        frame_bgr: np.ndarray,
        primary_detections: Sequence[DetectionResult],
    ) -> tuple[DetectionResult, ...]:
        return ()

    def close(self) -> None:
        pass
