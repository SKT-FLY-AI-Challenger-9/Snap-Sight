import numpy as np

from ai.on_device_cv.contracts import BoundingBox, FrameResult, TrackedObject
from ai.on_device_cv.visualization import draw_frame_result


def test_visualization_draws_overlay_without_mutating_input():
    frame = np.zeros((120, 200, 3), dtype=np.uint8)
    result = FrameResult(
        (
            TrackedObject(
                track_id=17,
                label="person",
                confidence=0.94,
                bbox=BoundingBox(0.80, 0.20, 0.99, 0.90),
            ),
        )
    )

    annotated = draw_frame_result(frame, result, fps=24.0)

    assert annotated.shape == frame.shape
    assert not annotated is frame
    assert np.count_nonzero(annotated) > 0
    assert np.count_nonzero(frame) == 0
