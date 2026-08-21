"""Detector -> optional extensions -> tracker orchestration."""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Self

import numpy as np

from ai.on_device_cv.contracts import FrameResult, validate_bgr_frame
from ai.on_device_cv.detectors.base import Detector
from ai.on_device_cv.extensions import DetectionExtension
from ai.on_device_cv.trackers.base import Tracker


@dataclass(frozen=True, slots=True)
class PipelineConfig:
    """Configuration for the model-independent part of the pipeline."""

    output_confidence_threshold: float = 0.25

    def __post_init__(self) -> None:
        if not 0.0 <= self.output_confidence_threshold <= 1.0:
            raise ValueError("output_confidence_threshold must be in [0, 1]")


class OnDeviceCVPipeline:
    """Detect and track every supported object in an input frame stream."""

    def __init__(
        self,
        detector: Detector,
        tracker: Tracker,
        *,
        extensions: Sequence[DetectionExtension] = (),
        config: PipelineConfig | None = None,
    ) -> None:
        self.detector = detector
        self.tracker = tracker
        self.extensions = tuple(extensions)
        self.config = config or PipelineConfig()
        self._loaded = False

    def load(self) -> None:
        if self._loaded:
            return
        loaded_extensions: list[DetectionExtension] = []
        try:
            self.detector.load()
            for extension in self.extensions:
                try:
                    extension.load()
                except BaseException:
                    extension.close()
                    raise
                loaded_extensions.append(extension)
            self._loaded = True
        except BaseException:
            for extension in reversed(loaded_extensions):
                extension.close()
            self.detector.close()
            raise

    def process(
        self,
        frame_bgr: np.ndarray,
        *,
        timestamp_s: float | None = None,
        motion_hint: tuple[float, float] | None = None,
    ) -> FrameResult:
        """Return the stable per-frame schema for one BGR ``uint8`` frame."""

        validate_bgr_frame(frame_bgr)
        primary_detections = tuple(self.detector.detect(frame_bgr))
        all_detections = list(primary_detections)
        for extension in self.extensions:
            all_detections.extend(extension.extend(frame_bgr, primary_detections))

        tracked_objects = self.tracker.update(
            all_detections, timestamp_s=timestamp_s, motion_hint=motion_hint
        )
        visible_objects = sorted(
            (
                item
                for item in tracked_objects
                if item.confidence >= self.config.output_confidence_threshold
            ),
            key=lambda item: item.track_id,
        )
        return FrameResult.from_objects(visible_objects)

    def reset(self) -> None:
        """Clear stream-specific tracking state before a new video."""

        self.tracker.reset()

    def close(self) -> None:
        if not self._loaded:
            return
        for extension in reversed(self.extensions):
            extension.close()
        self.detector.close()
        self._loaded = False

    def __enter__(self) -> Self:
        self.load()
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()
