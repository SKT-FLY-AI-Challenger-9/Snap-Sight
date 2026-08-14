import numpy as np
import pytest

from ai.on_device_cv.contracts import (
    BoundingBox,
    DetectionResult,
    FrameResult,
    TrackedObject,
    validate_bgr_frame,
)


def test_frame_result_matches_public_schema_exactly():
    result = FrameResult(
        objects=(
            TrackedObject(
                track_id=17,
                label="person",
                confidence=0.94,
                bbox=BoundingBox(x_min=0.31, y_min=0.12, x_max=0.68, y_max=0.91),
            ),
        )
    )

    assert result.to_dict() == {
        "objects": [
            {
                "track_id": 17,
                "label": "person",
                "confidence": 0.94,
                "bbox": {
                    "x_min": 0.31,
                    "y_min": 0.12,
                    "x_max": 0.68,
                    "y_max": 0.91,
                },
            }
        ]
    }


def test_internal_class_id_does_not_change_the_public_object_schema():
    tracked = TrackedObject(
        track_id=17,
        label="Person",
        confidence=0.94,
        bbox=BoundingBox(0.31, 0.12, 0.68, 0.91),
        class_id=0,
    )

    assert tracked.class_id == 0
    assert set(tracked.to_dict()) == {"track_id", "label", "confidence", "bbox"}


def test_bounding_box_clips_raw_detector_coordinates():
    assert BoundingBox.clipped(-0.2, 0.1, 1.3, 0.9) == BoundingBox(0.0, 0.1, 1.0, 0.9)
    assert BoundingBox.clipped(0.8, 0.1, 0.2, 0.9) is None
    assert BoundingBox.clipped(float("nan"), 0.1, 0.8, 0.9) is None


def test_frame_result_rejects_duplicate_track_ids():
    bbox = BoundingBox(0.1, 0.1, 0.2, 0.2)
    with pytest.raises(ValueError, match="duplicate"):
        FrameResult(
            (
                TrackedObject(1, "person", 0.9, bbox),
                TrackedObject(1, "chair", 0.8, bbox),
            )
        )


@pytest.mark.parametrize("track_id", [True, 1.0, 1.5, "1"])
def test_tracked_object_requires_a_real_integer_track_id(track_id):
    with pytest.raises(ValueError, match="positive integer"):
        TrackedObject(track_id, "person", 0.9, BoundingBox(0.1, 0.1, 0.2, 0.2))


@pytest.mark.parametrize("class_id", [True, 1.0, 1.5, "1"])
def test_detection_requires_an_integer_class_id_when_present(class_id):
    with pytest.raises(ValueError, match="non-negative integer"):
        DetectionResult("person", 0.9, BoundingBox(0.1, 0.1, 0.2, 0.2), class_id)


@pytest.mark.parametrize(
    "frame",
    [
        np.zeros((10, 10), dtype=np.uint8),
        np.zeros((10, 10, 4), dtype=np.uint8),
        np.zeros((10, 10, 3), dtype=np.float32),
    ],
)
def test_validate_bgr_frame_rejects_non_bgr_uint8_inputs(frame):
    with pytest.raises(ValueError):
        validate_bgr_frame(frame)
