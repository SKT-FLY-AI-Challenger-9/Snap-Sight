"""Backend-neutral data contracts used by the on-device CV pipeline."""

from __future__ import annotations

import math
from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any

import numpy as np


@dataclass(frozen=True, slots=True)
class BoundingBox:
    """A normalized ``xyxy`` box in the original upright input frame."""

    x_min: float
    y_min: float
    x_max: float
    y_max: float

    def __post_init__(self) -> None:
        coordinates = (self.x_min, self.y_min, self.x_max, self.y_max)
        if not all(math.isfinite(value) for value in coordinates):
            raise ValueError("Bounding-box coordinates must be finite")
        if not all(0.0 <= value <= 1.0 for value in coordinates):
            raise ValueError(f"Bounding box must be normalized to [0, 1]: {coordinates}")
        if self.x_min >= self.x_max or self.y_min >= self.y_max:
            raise ValueError(f"Bounding box must have positive area: {coordinates}")

    @classmethod
    def clipped(
        cls,
        x_min: float,
        y_min: float,
        x_max: float,
        y_max: float,
    ) -> BoundingBox | None:
        """Clip raw coordinates to the contract, dropping invalid boxes."""

        coordinates = (x_min, y_min, x_max, y_max)
        if not all(math.isfinite(value) for value in coordinates):
            return None

        clipped = tuple(min(1.0, max(0.0, float(value))) for value in coordinates)
        if clipped[0] >= clipped[2] or clipped[1] >= clipped[3]:
            return None
        return cls(*clipped)

    def to_pixels(self, width: int, height: int) -> tuple[int, int, int, int]:
        """Convert the normalized box to pixel coordinates for display."""

        if width <= 0 or height <= 0:
            raise ValueError("Frame width and height must be positive")
        return (
            round(self.x_min * width),
            round(self.y_min * height),
            round(self.x_max * width),
            round(self.y_max * height),
        )

    def iou(self, other: BoundingBox) -> float:
        """Return intersection-over-union with another normalized box."""

        intersection_width = max(0.0, min(self.x_max, other.x_max) - max(self.x_min, other.x_min))
        intersection_height = max(0.0, min(self.y_max, other.y_max) - max(self.y_min, other.y_min))
        intersection = intersection_width * intersection_height
        own_area = (self.x_max - self.x_min) * (self.y_max - self.y_min)
        other_area = (other.x_max - other.x_min) * (other.y_max - other.y_min)
        union = own_area + other_area - intersection
        return intersection / union if union > 0.0 else 0.0

    def to_dict(self) -> dict[str, float]:
        return {
            "x_min": float(self.x_min),
            "y_min": float(self.y_min),
            "x_max": float(self.x_max),
            "y_max": float(self.y_max),
        }


@dataclass(frozen=True, slots=True)
class DetectionResult:
    """Post-processed detector output independent of model tensor formats."""

    label: str
    confidence: float
    bbox: BoundingBox
    class_id: int | None = None

    def __post_init__(self) -> None:
        if not self.label or not self.label.strip():
            raise ValueError("Detection label must not be empty")
        if not math.isfinite(self.confidence) or not 0.0 <= self.confidence <= 1.0:
            raise ValueError(f"Detection confidence must be in [0, 1]: {self.confidence}")
        if self.class_id is not None and (type(self.class_id) is not int or self.class_id < 0):
            raise ValueError("class_id must be a non-negative integer when provided")


@dataclass(frozen=True, slots=True)
class TrackedObject:
    """A currently observed detection with a stream-persistent track ID."""

    track_id: int
    label: str
    confidence: float
    bbox: BoundingBox

    def __post_init__(self) -> None:
        if type(self.track_id) is not int or self.track_id <= 0:
            raise ValueError("track_id must be a positive integer")
        if not self.label or not self.label.strip():
            raise ValueError("Tracked-object label must not be empty")
        if not math.isfinite(self.confidence) or not 0.0 <= self.confidence <= 1.0:
            raise ValueError(f"Tracked-object confidence must be in [0, 1]: {self.confidence}")

    def to_dict(self) -> dict[str, Any]:
        return {
            "track_id": self.track_id,
            "label": self.label,
            "confidence": float(self.confidence),
            "bbox": self.bbox.to_dict(),
        }


@dataclass(frozen=True, slots=True)
class FrameResult:
    """Public per-frame response returned by :class:`OnDeviceCVPipeline`."""

    objects: tuple[TrackedObject, ...] = ()

    def __post_init__(self) -> None:
        track_ids = [item.track_id for item in self.objects]
        if len(track_ids) != len(set(track_ids)):
            raise ValueError("A frame cannot contain duplicate track IDs")

    @classmethod
    def from_objects(cls, objects: Iterable[TrackedObject]) -> FrameResult:
        return cls(tuple(objects))

    def to_dict(self) -> dict[str, list[dict[str, Any]]]:
        """Serialize to the stable Snap-Sight API schema."""

        return {"objects": [item.to_dict() for item in self.objects]}


def validate_bgr_frame(frame_bgr: np.ndarray) -> None:
    """Validate the single frame format shared by all PC detector adapters."""

    if not isinstance(frame_bgr, np.ndarray):
        raise TypeError("frame must be a numpy array")
    if frame_bgr.dtype != np.uint8:
        raise ValueError(f"frame dtype must be uint8, got {frame_bgr.dtype}")
    if frame_bgr.ndim != 3 or frame_bgr.shape[2] != 3:
        raise ValueError(f"frame shape must be HxWx3, got {frame_bgr.shape}")
    if frame_bgr.shape[0] <= 0 or frame_bgr.shape[1] <= 0:
        raise ValueError("frame must not be empty")
