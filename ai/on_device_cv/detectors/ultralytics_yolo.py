"""Ultralytics YOLO adapter that hides all model-specific result objects."""

from __future__ import annotations

import os
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

from ai.on_device_cv.contracts import BoundingBox, DetectionResult, validate_bgr_frame
from ai.taxonomy import OBJECTS365_YOLO26, ObjectTaxonomy

ModelFactory = Callable[..., Any]


@dataclass(frozen=True, slots=True)
class UltralyticsDetectorConfig:
    """Settings owned specifically by the Ultralytics detector adapter."""

    model: str = "yolo26n-kr170-v5.pt"
    input_size: int = 640
    confidence_threshold: float = 0.10
    max_detections: int = 300
    device: str = "cpu"
    runtime_directory: Path = Path(".runtime")
    expected_taxonomy: ObjectTaxonomy | None = OBJECTS365_YOLO26

    def __post_init__(self) -> None:
        if not self.model:
            raise ValueError("model must not be empty")
        if self.input_size <= 0:
            raise ValueError("input_size must be positive")
        if not 0.0 <= self.confidence_threshold <= 1.0:
            raise ValueError("confidence_threshold must be in [0, 1]")
        if self.max_detections <= 0:
            raise ValueError("max_detections must be positive")
        if not self.device:
            raise ValueError("device must not be empty")
        if self.expected_taxonomy is not None and not isinstance(
            self.expected_taxonomy, ObjectTaxonomy
        ):
            raise TypeError("expected_taxonomy must be an ObjectTaxonomy or null")


class UltralyticsYoloDetector:
    """Run a YOLO detection checkpoint and emit the shared detection contract.

    The default checkpoint is the Korean-domain 170-class model that ships with the
    app. A compatible fine-tuned checkpoint can be supplied via
    :class:`UltralyticsDetectorConfig` without changing pipeline or tracker code.
    A native TFLite deployment should use its own adapter and config for explicit
    runtime control.
    """

    def __init__(
        self,
        config: UltralyticsDetectorConfig | None = None,
        *,
        model_factory: ModelFactory | None = None,
    ) -> None:
        self.config = config or UltralyticsDetectorConfig()
        self._model_factory = model_factory
        self._model: Any | None = None

    def load(self) -> None:
        if self._model is not None:
            return

        runtime_directory = self.config.runtime_directory.resolve()
        yolo_config_directory = runtime_directory / "ultralytics"
        matplotlib_directory = runtime_directory / "matplotlib"
        yolo_config_directory.mkdir(parents=True, exist_ok=True)
        matplotlib_directory.mkdir(parents=True, exist_ok=True)
        environment = {
            "YOLO_CONFIG_DIR": str(yolo_config_directory),
            "MPLCONFIGDIR": str(matplotlib_directory),
        }
        previous_environment = {name: os.environ.get(name) for name in environment}
        os.environ.update(environment)
        try:
            factory = self._model_factory
            if factory is None:
                try:
                    from ultralytics import YOLO
                except ImportError as exc:
                    raise RuntimeError(
                        "Ultralytics is not installed. Run "
                        "`python -m pip install -r requirements.txt`."
                    ) from exc
                factory = YOLO

            # The model name is intentionally passed through. Official names are
            # downloaded by Ultralytics; compatible local checkpoints work too.
            model = factory(self.config.model, task="detect")
            if self.config.expected_taxonomy is not None:
                names = getattr(model, "names", None)
                if names is None:
                    raise ValueError("Model does not expose class names for taxonomy validation")
                self.config.expected_taxonomy.validate_model_names(names)
            self._model = model
        finally:
            for name, previous_value in previous_environment.items():
                if previous_value is None:
                    os.environ.pop(name, None)
                else:
                    os.environ[name] = previous_value

    def detect(self, frame_bgr: np.ndarray) -> list[DetectionResult]:
        validate_bgr_frame(frame_bgr)
        if self._model is None:
            raise RuntimeError("Detector is not loaded; call load() before detect()")

        predictions = self._model.predict(
            source=frame_bgr,
            imgsz=self.config.input_size,
            conf=self.config.confidence_threshold,
            max_det=self.config.max_detections,
            classes=None,
            device=self.config.device,
            verbose=False,
        )
        if not predictions:
            return []

        result = predictions[0]
        boxes = getattr(result, "boxes", None)
        if boxes is None:
            return []

        coordinates = _to_numpy(boxes.xyxyn)
        confidences = _to_numpy(boxes.conf).reshape(-1)
        class_ids = _to_numpy(boxes.cls).reshape(-1).astype(np.int64)
        if coordinates.ndim != 2 or coordinates.shape[1] != 4:
            raise RuntimeError(f"Unexpected YOLO bbox shape: {coordinates.shape}")
        if not (len(coordinates) == len(confidences) == len(class_ids)):
            raise RuntimeError("YOLO result arrays have inconsistent lengths")

        names = getattr(result, "names", getattr(self._model, "names", {}))
        detections: list[DetectionResult] = []
        for raw_box, raw_confidence, raw_class_id in zip(
            coordinates,
            confidences,
            class_ids,
            strict=True,
        ):
            confidence = float(raw_confidence)
            if not np.isfinite(confidence) or confidence < self.config.confidence_threshold:
                continue

            bbox = BoundingBox.clipped(*(float(value) for value in raw_box))
            if bbox is None:
                continue

            class_id = int(raw_class_id)
            label = _class_name(names, class_id)
            detections.append(
                DetectionResult(
                    label=label,
                    confidence=min(1.0, confidence),
                    bbox=bbox,
                    class_id=class_id,
                )
            )
            if len(detections) >= self.config.max_detections:
                break
        return detections

    def close(self) -> None:
        self._model = None


def _to_numpy(value: Any) -> np.ndarray:
    """Convert torch/LiteRT-style tensors without leaking them past this module."""

    if hasattr(value, "detach"):
        value = value.detach()
    if hasattr(value, "cpu"):
        value = value.cpu()
    if hasattr(value, "numpy"):
        value = value.numpy()
    return np.asarray(value)


def _class_name(names: Any, class_id: int) -> str:
    if isinstance(names, dict):
        name = names.get(class_id)
    else:
        try:
            name = names[class_id]
        except (IndexError, KeyError, TypeError):
            name = None
    return str(name).strip().lower() if name is not None else f"class_{class_id}"
