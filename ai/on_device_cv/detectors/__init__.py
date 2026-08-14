"""Object detector adapters."""

from ai.on_device_cv.detectors.base import Detector
from ai.on_device_cv.detectors.ultralytics_yolo import (
    UltralyticsDetectorConfig,
    UltralyticsYoloDetector,
)

__all__ = ["Detector", "UltralyticsDetectorConfig", "UltralyticsYoloDetector"]
