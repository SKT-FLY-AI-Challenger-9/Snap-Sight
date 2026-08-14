import pytest

from ai.on_device_cv.contracts import BoundingBox, DetectionResult
from ai.on_device_cv.trackers import ByteTrackLiteConfig, ByteTrackLiteTracker


def detection(
    x_min: float,
    y_min: float,
    x_max: float,
    y_max: float,
    *,
    label: str = "person",
    confidence: float = 0.9,
    class_id: int | None = None,
) -> DetectionResult:
    return DetectionResult(
        label,
        confidence,
        BoundingBox(x_min, y_min, x_max, y_max),
        class_id=class_id,
    )


def test_single_moving_object_keeps_track_id():
    tracker = ByteTrackLiteTracker()

    first = tracker.update([detection(0.10, 0.10, 0.30, 0.40)])
    second = tracker.update([detection(0.12, 0.10, 0.32, 0.40)])
    third = tracker.update([detection(0.15, 0.10, 0.35, 0.40)])

    assert [item.track_id for item in first] == [1]
    assert [item.track_id for item in second] == [1]
    assert [item.track_id for item in third] == [1]


def test_tracker_preserves_detector_class_id_for_target_selection():
    tracker = ByteTrackLiteTracker()
    observed = tracker.update(
        [
            DetectionResult(
                "Person",
                0.9,
                BoundingBox(0.1, 0.1, 0.3, 0.4),
                class_id=0,
            )
        ]
    )

    assert observed[0].class_id == 0


def test_association_prefers_class_id_when_labels_flicker_at_the_same_location():
    tracker = ByteTrackLiteTracker()
    shared_box = (0.1, 0.1, 0.4, 0.8)

    first = tracker.update(
        [
            detection(*shared_box, label="alpha", class_id=0),
            detection(*shared_box, label="beta", class_id=1),
        ]
    )
    second = tracker.update(
        [
            detection(*shared_box, label="zeta", class_id=0),
            detection(*shared_box, label="alpha", class_id=1),
        ]
    )

    assert [(item.track_id, item.class_id) for item in first] == [(1, 0), (2, 1)]
    assert [(item.track_id, item.class_id) for item in second] == [(1, 0), (2, 1)]


def test_multiple_objects_are_independent_when_detection_order_changes():
    tracker = ByteTrackLiteTracker()
    left = detection(0.05, 0.10, 0.25, 0.50)
    right = detection(0.65, 0.10, 0.85, 0.50)

    first = tracker.update([right, left])
    second = tracker.update(
        [
            detection(0.07, 0.10, 0.27, 0.50),
            detection(0.63, 0.10, 0.83, 0.50),
        ]
    )

    assert [(item.track_id, item.bbox.x_min) for item in first] == [(1, 0.05), (2, 0.65)]
    assert [(item.track_id, item.bbox.x_min) for item in second] == [(1, 0.07), (2, 0.63)]


def test_low_confidence_detection_recovers_track_but_does_not_create_one():
    tracker = ByteTrackLiteTracker(
        ByteTrackLiteConfig(
            track_activation_threshold=0.25,
            minimum_matching_confidence=0.10,
        )
    )

    assert tracker.update([detection(0.1, 0.1, 0.3, 0.4)])[0].track_id == 1
    low_results = tracker.update(
        [
            detection(0.11, 0.1, 0.31, 0.4, confidence=0.15),
            detection(0.70, 0.1, 0.90, 0.4, confidence=0.15),
        ]
    )

    assert [(item.track_id, item.confidence) for item in low_results] == [(1, 0.15)]


def test_track_survives_configured_missing_frame_buffer_then_expires():
    tracker = ByteTrackLiteTracker(ByteTrackLiteConfig(lost_track_buffer=2))
    box = detection(0.2, 0.2, 0.4, 0.5)

    assert tracker.update([box])[0].track_id == 1
    assert tracker.update([]) == []
    assert tracker.update([]) == []
    assert tracker.update([box])[0].track_id == 1

    tracker.update([])
    tracker.update([])
    tracker.update([])
    assert tracker.update([box])[0].track_id == 2


def test_moving_object_label_flicker_does_not_fragment_track():
    tracker = ByteTrackLiteTracker()
    for offset in (0.00, 0.02, 0.04):
        result = tracker.update(
            [detection(0.20 + offset, 0.20, 0.50 + offset, 0.80, label="person")]
        )
        assert result[0].track_id == 1

    flicker = tracker.update([detection(0.27, 0.20, 0.57, 0.80, label="human")])

    assert flicker[0].track_id == 1
    assert flicker[0].label == "human"
    assert flicker[0].confidence == 0.9


def test_low_confidence_detection_cannot_revive_a_lost_track():
    tracker = ByteTrackLiteTracker(ByteTrackLiteConfig(lost_track_buffer=2))
    box = detection(0.2, 0.2, 0.5, 0.8)

    assert tracker.update([box])[0].track_id == 1
    assert tracker.update([]) == []
    assert tracker.update([detection(0.2, 0.2, 0.5, 0.8, confidence=0.15)]) == []
    assert tracker.update([]) == []

    # The hidden low-confidence false positive did not renew the lost track.
    returned = tracker.update([box])
    assert returned[0].track_id == 2


def test_zero_iou_never_matches_even_when_threshold_is_zero():
    tracker = ByteTrackLiteTracker(
        ByteTrackLiteConfig(first_match_iou_threshold=0.0, second_match_iou_threshold=0.0)
    )

    assert tracker.update([detection(0.0, 0.1, 0.1, 0.3)])[0].track_id == 1
    jumped = tracker.update([detection(0.9, 0.1, 1.0, 0.3)])

    assert jumped[0].track_id == 2


def test_invalid_update_does_not_advance_tracker_state():
    tracker = ByteTrackLiteTracker()
    box = detection(0.2, 0.2, 0.5, 0.8)
    assert tracker.update([box])[0].track_id == 1

    with pytest.raises(TypeError):
        tracker.update([object()])

    assert tracker.update([box])[0].track_id == 1


def test_irregular_frame_intervals_can_use_timestamps_for_motion_prediction():
    tracker = ByteTrackLiteTracker(ByteTrackLiteConfig(velocity_momentum=0.0))

    first = tracker.update([detection(0.10, 0.1, 0.30, 0.4)], timestamp_s=0.0)
    second = tracker.update([detection(0.20, 0.1, 0.40, 0.4)], timestamp_s=0.1)
    third = tracker.update([detection(0.60, 0.1, 0.80, 0.4)], timestamp_s=0.5)

    assert [item.track_id for item in first] == [1]
    assert [item.track_id for item in second] == [1]
    assert [item.track_id for item in third] == [1]


def test_timestamps_must_be_consistent_and_strictly_increasing():
    tracker = ByteTrackLiteTracker()
    box = detection(0.2, 0.2, 0.5, 0.8)
    tracker.update([box], timestamp_s=1.0)

    with pytest.raises(ValueError, match="increase strictly"):
        tracker.update([box], timestamp_s=1.0)
    with pytest.raises(ValueError, match="every frame"):
        tracker.update([box])

    # Failed validation did not mutate time or association state.
    assert tracker.update([box], timestamp_s=2.0)[0].track_id == 1


def test_constant_velocity_prediction_preserves_ids_when_objects_cross():
    tracker = ByteTrackLiteTracker()
    frames = [
        [detection(0.10, 0.10, 0.30, 0.40), detection(0.70, 0.10, 0.90, 0.40)],
        [detection(0.20, 0.10, 0.40, 0.40), detection(0.60, 0.10, 0.80, 0.40)],
        [detection(0.32, 0.10, 0.52, 0.40), detection(0.48, 0.10, 0.68, 0.40)],
        [detection(0.46, 0.10, 0.66, 0.40), detection(0.34, 0.10, 0.54, 0.40)],
        [detection(0.60, 0.10, 0.80, 0.40), detection(0.20, 0.10, 0.40, 0.40)],
    ]

    results = [tracker.update(frame) for frame in frames]

    assert [(item.track_id, item.bbox.x_min) for item in results[-1]] == [(1, 0.60), (2, 0.20)]


def test_global_assignment_preserves_both_tracks_when_greedy_would_drop_one():
    tracker = ByteTrackLiteTracker()
    tracker.update(
        [
            detection(0.00, 0.10, 0.40, 0.40),
            detection(0.20, 0.10, 0.60, 0.40),
        ]
    )

    assigned = tracker.update(
        [
            detection(0.05, 0.10, 0.45, 0.40),
            detection(0.00, 0.10, 0.25, 0.40),
        ]
    )

    assert [(item.track_id, item.bbox.x_min) for item in assigned] == [(1, 0.00), (2, 0.05)]


def test_reset_clears_stream_state_and_restarts_ids():
    tracker = ByteTrackLiteTracker()
    tracker.update([detection(0.1, 0.1, 0.3, 0.4)])
    tracker.update([detection(0.6, 0.1, 0.8, 0.4)])

    tracker.reset()

    restarted = tracker.update([detection(0.6, 0.1, 0.8, 0.4)])
    assert [item.track_id for item in restarted] == [1]
