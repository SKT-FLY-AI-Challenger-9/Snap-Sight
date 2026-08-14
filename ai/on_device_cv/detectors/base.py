"""Detector interface kept independent from any model runtime."""

from __future__ import annotations

from collections.abc import Sequence
from typing import Protocol

import numpy as np

from ai.on_device_cv.contracts import DetectionResult


class Detector(Protocol):
    """Contract implemented by PyTorch today and TFLite later."""

    def load(self) -> None:
        """Load model resources."""

    def detect(self, frame_bgr: np.ndarray) -> Sequence[DetectionResult]:
        """Return only common, post-processed detections."""

    def close(self) -> None:
        """Release model resources."""
