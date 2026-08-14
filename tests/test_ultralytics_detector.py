import os
from types import SimpleNamespace

import numpy as np
import pytest

from ai.on_device_cv.detectors import UltralyticsDetectorConfig, UltralyticsYoloDetector
from ai.taxonomy import OBJECTS365_YOLO26


class FakeModel:
    def __init__(self, result):
        self.result = result
        self.names = getattr(result, "names", None)
        self.predict_kwargs = None

    def predict(self, **kwargs):
        self.predict_kwargs = kwargs
        return [self.result]


def test_ultralytics_adapter_normalizes_and_hides_raw_results(tmp_path, monkeypatch):
    monkeypatch.setenv("YOLO_CONFIG_DIR", "existing-yolo-config")
    monkeypatch.setenv("MPLCONFIGDIR", "existing-matplotlib-config")
    boxes = SimpleNamespace(
        xyxyn=np.array(
            [
                [-0.1, 0.2, 1.1, 0.9],
                [0.2, 0.2, 0.4, 0.4],
                [0.8, 0.2, 0.7, 0.4],
            ],
            dtype=np.float32,
        ),
        conf=np.array([0.9, 0.2, 0.8], dtype=np.float32),
        cls=np.array([0, 1, 1], dtype=np.float32),
    )
    result = SimpleNamespace(boxes=boxes, names={0: "Person", 1: "Bottle"})
    fake_model = FakeModel(result)
    factory_calls = []

    def model_factory(model, **kwargs):
        factory_calls.append((model, kwargs))
        return fake_model

    config = UltralyticsDetectorConfig(
        model="future_objects365_subset.pt",
        input_size=512,
        confidence_threshold=0.25,
        max_detections=10,
        runtime_directory=tmp_path,
        expected_taxonomy=None,
    )
    detector = UltralyticsYoloDetector(config, model_factory=model_factory)
    detector.load()
    detections = detector.detect(np.zeros((100, 200, 3), dtype=np.uint8))

    assert os.environ["YOLO_CONFIG_DIR"] == "existing-yolo-config"
    assert os.environ["MPLCONFIGDIR"] == "existing-matplotlib-config"
    assert factory_calls == [("future_objects365_subset.pt", {"task": "detect"})]
    assert fake_model.predict_kwargs["classes"] is None
    assert fake_model.predict_kwargs["conf"] == 0.25
    assert len(detections) == 1
    assert detections[0].label == "person"
    assert detections[0].class_id == 0
    assert detections[0].bbox.to_dict() == {
        "x_min": 0.0,
        "y_min": pytest.approx(0.2),
        "x_max": 1.0,
        "y_max": pytest.approx(0.9),
    }


def test_detector_requires_explicit_load():
    detector = UltralyticsYoloDetector()
    frame = np.zeros((10, 10, 3), dtype=np.uint8)

    with pytest.raises(RuntimeError, match="load"):
        detector.detect(frame)


def test_detector_config_rejects_an_invalid_taxonomy_adapter():
    with pytest.raises(TypeError, match="expected_taxonomy"):
        UltralyticsDetectorConfig(expected_taxonomy=object())


def test_detector_can_fail_fast_when_model_taxonomy_is_reordered(tmp_path):
    result = SimpleNamespace(boxes=None, names={0: "person", 1: "bottle"})
    fake_model = FakeModel(result)
    detector = UltralyticsYoloDetector(
        UltralyticsDetectorConfig(
            model="wrong-taxonomy.pt",
            runtime_directory=tmp_path,
            expected_taxonomy=OBJECTS365_YOLO26,
        ),
        model_factory=lambda *args, **kwargs: fake_model,
    )

    with pytest.raises(ValueError, match="taxonomy mismatch"):
        detector.load()

    with pytest.raises(RuntimeError, match="load"):
        detector.detect(np.zeros((10, 10, 3), dtype=np.uint8))


def test_detector_accepts_the_exact_objects365_taxonomy(tmp_path):
    result = SimpleNamespace(
        boxes=None,
        names=dict(enumerate(OBJECTS365_YOLO26.labels)),
    )
    fake_model = FakeModel(result)
    detector = UltralyticsYoloDetector(
        UltralyticsDetectorConfig(
            model="objects365.pt",
            runtime_directory=tmp_path,
            expected_taxonomy=OBJECTS365_YOLO26,
        ),
        model_factory=lambda *args, **kwargs: fake_model,
    )

    detector.load()

    assert detector.detect(np.zeros((10, 10, 3), dtype=np.uint8)) == []
