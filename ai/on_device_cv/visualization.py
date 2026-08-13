"""OpenCV overlay utilities for the PC prototype."""

from __future__ import annotations

import cv2
import numpy as np

from ai.on_device_cv.contracts import FrameResult


def draw_frame_result(
    frame_bgr: np.ndarray,
    result: FrameResult,
    *,
    fps: float | None = None,
    copy: bool = True,
) -> np.ndarray:
    """Draw bbox, label, confidence, and track ID for every visible object."""

    canvas = frame_bgr.copy() if copy else frame_bgr
    height, width = canvas.shape[:2]
    thickness = max(1, round(min(width, height) / 480))
    font_scale = max(0.45, min(0.80, min(width, height) / 900))

    for tracked_object in result.objects:
        x_min, y_min, x_max, y_max = tracked_object.bbox.to_pixels(width, height)
        x_min = min(width - 1, max(0, x_min))
        y_min = min(height - 1, max(0, y_min))
        x_max = min(width - 1, max(0, x_max))
        y_max = min(height - 1, max(0, y_max))
        color = _track_color(tracked_object.track_id)
        cv2.rectangle(canvas, (x_min, y_min), (x_max, y_max), color, thickness)

        caption = (
            f"#{tracked_object.track_id} {tracked_object.label} " f"{tracked_object.confidence:.2f}"
        )
        (text_width, text_height), baseline = cv2.getTextSize(
            caption,
            cv2.FONT_HERSHEY_SIMPLEX,
            font_scale,
            thickness,
        )
        label_width = min(width, text_width + 8)
        label_left = min(x_min, max(0, width - label_width))
        label_top = max(0, y_min - text_height - baseline - 6)
        label_right = min(width - 1, label_left + label_width)
        cv2.rectangle(
            canvas,
            (label_left, label_top),
            (label_right, min(height - 1, label_top + text_height + baseline + 6)),
            color,
            -1,
        )
        cv2.putText(
            canvas,
            caption,
            (label_left + 4, label_top + text_height + 2),
            cv2.FONT_HERSHEY_SIMPLEX,
            font_scale,
            (255, 255, 255),
            thickness,
            cv2.LINE_AA,
        )

    if fps is not None:
        cv2.putText(
            canvas,
            f"FPS {fps:.1f} | objects {len(result.objects)}",
            (12, 28),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.65,
            (40, 240, 40),
            2,
            cv2.LINE_AA,
        )
    return canvas


def _track_color(track_id: int) -> tuple[int, int, int]:
    """Generate a deterministic, high-contrast BGR color from a track ID."""

    return (
        64 + (track_id * 47) % 192,
        64 + (track_id * 89) % 192,
        64 + (track_id * 137) % 192,
    )
