"""Normalize analysis-stream JPEG pixels into display-upright JPEGs."""

from __future__ import annotations

import io

from PIL import Image, ImageOps, UnidentifiedImageError

ALLOWED_ROTATION_DEGREES = {0, 90, 180, 270}
MAX_CAPTURE_IMAGE_PIXELS = 50_000_000


class InvalidCaptureImage(ValueError):
    """Raised when uploaded bytes cannot be normalized as a JPEG image."""


def normalize_uploaded_jpeg(content: bytes, rotation_degrees: int = 0) -> bytes:
    """Validate a JPEG and rotate it clockwise into display orientation.

    CameraX's ``rotationDegrees`` is the clockwise transform needed to make an
    analysis frame upright. A zero-degree, EXIF-upright image is returned
    byte-for-byte so it is not needlessly degraded.
    """
    if rotation_degrees not in ALLOWED_ROTATION_DEGREES:
        raise ValueError("rotation_degrees must be one of 0, 90, 180 or 270")
    try:
        with Image.open(io.BytesIO(content)) as source:
            if source.format != "JPEG":
                raise InvalidCaptureImage("capture frame is not a JPEG image")
            if source.width * source.height > MAX_CAPTURE_IMAGE_PIXELS:
                raise InvalidCaptureImage("capture frame dimensions are too large")
            orientation = source.getexif().get(274)
            if rotation_degrees == 0 and orientation in {None, 1}:
                source.verify()
                return content
            source.load()
            image = ImageOps.exif_transpose(source)
            if rotation_degrees:
                image = image.rotate(-rotation_degrees, expand=True)
            if image.mode not in {"RGB", "L"}:
                image = image.convert("RGB")
            output = io.BytesIO()
            image.save(output, format="JPEG", quality=90)
            return output.getvalue()
    except (
        Image.DecompressionBombError,
        UnidentifiedImageError,
        OSError,
        SyntaxError,
        ValueError,
    ) as exc:
        if isinstance(exc, InvalidCaptureImage):
            raise
        raise InvalidCaptureImage("capture frame is not a decodable JPEG") from exc


def normalize_candidate_jpeg(content: bytes, rotation_degrees: int) -> bytes:
    """Backward-compatible candidate-specific alias."""
    return normalize_uploaded_jpeg(content, rotation_degrees)
