from collections import deque

import numpy as np
import pytest

from ai.on_device_cv.contracts import BoundingBox, DetectionResult
from ai.on_device_cv.pipeline import OnDeviceCVPipeline, PipelineConfig
from ai.on_device_cv.target_selection import TargetSelector
from ai.on_device_cv.trackers import ByteTrackLiteConfig, ByteTrackLiteTracker
from ai.target_spec import SubjectType, TargetSpec, TargetSpecSource

PERSON = DetectionResult("person", 0.9, BoundingBox(0.1, 0.1, 0.4, 0.8))
LOW_PERSON = DetectionResult("person", 0.15, BoundingBox(0.11, 0.1, 0.41, 0.8))
RETURNED_PERSON = DetectionResult("person", 0.85, BoundingBox(0.12, 0.1, 0.42, 0.8))
BOTTLE = DetectionResult("bottle", 0.8, BoundingBox(0.7, 0.4, 0.8, 0.8))


class SequenceDetector:
    def __init__(self, frames):
        self.frames = deque(frames)
        self.loaded = False
        self.closed = False

    def load(self):
        self.loaded = True

    def detect(self, frame_bgr):
        assert self.loaded
        return self.frames.popleft()

    def close(self):
        self.closed = True


class BottleExtension:
    def __init__(self):
        self.loaded = False
        self.closed = False

    def load(self):
        self.loaded = True

    def extend(self, frame_bgr, primary_detections):
        assert self.loaded
        assert tuple(primary_detections) == (PERSON,)
        return [BOTTLE]

    def close(self):
        self.closed = True


class FailingExtension(BottleExtension):
    def load(self):
        raise RuntimeError("face model failed to load")


def test_pipeline_combines_extensions_and_returns_only_public_contract():
    detector = SequenceDetector([[PERSON]])
    extension = BottleExtension()
    pipeline = OnDeviceCVPipeline(
        detector,
        ByteTrackLiteTracker(),
        extensions=[extension],
    )
    frame = np.zeros((48, 64, 3), dtype=np.uint8)

    pipeline.load()
    result = pipeline.process(frame)
    pipeline.close()

    assert result.to_dict() == {
        "objects": [
            {
                "track_id": 1,
                "label": "person",
                "confidence": 0.9,
                "bbox": {"x_min": 0.1, "y_min": 0.1, "x_max": 0.4, "y_max": 0.8},
            },
            {
                "track_id": 2,
                "label": "bottle",
                "confidence": 0.8,
                "bbox": {"x_min": 0.7, "y_min": 0.4, "x_max": 0.8, "y_max": 0.8},
            },
        ]
    }
    assert detector.closed
    assert extension.closed


def test_pipeline_hides_low_confidence_match_while_preserving_its_id():
    detector = SequenceDetector([[PERSON], [LOW_PERSON], [RETURNED_PERSON]])
    tracker = ByteTrackLiteTracker(
        ByteTrackLiteConfig(
            track_activation_threshold=0.25,
            minimum_matching_confidence=0.10,
        )
    )
    pipeline = OnDeviceCVPipeline(
        detector,
        tracker,
        config=PipelineConfig(output_confidence_threshold=0.25),
    )
    frame = np.zeros((48, 64, 3), dtype=np.uint8)
    pipeline.load()

    first = pipeline.process(frame)
    hidden = pipeline.process(frame)
    returned = pipeline.process(frame)

    assert first.objects[0].track_id == 1
    assert hidden.to_dict() == {"objects": []}
    assert returned.objects[0].track_id == 1


def test_changing_target_spec_does_not_reset_all_object_track_ids():
    detector = SequenceDetector([[PERSON, BOTTLE], [PERSON, BOTTLE]])
    pipeline = OnDeviceCVPipeline(detector, ByteTrackLiteTracker())
    selector = TargetSelector()
    frame = np.zeros((48, 64, 3), dtype=np.uint8)
    pipeline.load()

    first = pipeline.process(frame)
    people = selector.select(
        first,
        TargetSpec(
            session_id="session-1",
            raw_text="사람을 찍어줘",
            source=TargetSpecSource.ONDEVICE,
        ),
    )
    second = pipeline.process(frame)
    objects = selector.select(
        second,
        TargetSpec(
            session_id="session-1",
            raw_text="사물을 찍어줘",
            source=TargetSpecSource.ONDEVICE,
            subject_type=SubjectType.OBJECT,
        ),
    )
    pipeline.close()

    assert [item.track_id for item in people.candidates] == [1]
    assert [item.track_id for item in objects.candidates] == [2]


def test_pipeline_rolls_back_loaded_resources_when_an_extension_fails():
    detector = SequenceDetector([])
    loaded_extension = BottleExtension()
    failing_extension = FailingExtension()
    pipeline = OnDeviceCVPipeline(
        detector,
        ByteTrackLiteTracker(),
        extensions=[loaded_extension, failing_extension],
    )

    with pytest.raises(RuntimeError, match="failed to load"):
        pipeline.load()

    assert detector.closed
    assert loaded_extension.closed
    assert failing_extension.closed

    # close() is safe after load() already performed rollback.
    pipeline.close()
