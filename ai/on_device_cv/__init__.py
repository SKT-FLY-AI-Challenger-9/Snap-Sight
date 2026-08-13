"""Detector-agnostic object detection and multi-object tracking pipeline."""

from ai.on_device_cv.contracts import BoundingBox, DetectionResult, FrameResult, TrackedObject
from ai.on_device_cv.pipeline import OnDeviceCVPipeline, PipelineConfig

__all__ = [
    "BoundingBox",
    "DetectionResult",
    "FrameResult",
    "OnDeviceCVPipeline",
    "PipelineConfig",
    "TrackedObject",
]
