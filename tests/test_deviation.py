"""backend/judgment/deviation.py에 대한 네트워크 없는 단위 테스트."""

import pytest

from backend.judgment.deviation import DetectionSignal, calculate_deviation


def test_centered_correct_size_has_zero_deviation():
    detection = DetectionSignal(center_x=0.5, area_ratio=0.12)
    result = calculate_deviation(detection, "full_body")
    assert result.subject_detected is True
    assert result.x_deviation == 0.0
    assert result.size_deviation == 0.0


def test_left_of_center_has_negative_x_deviation():
    detection = DetectionSignal(center_x=0.3, area_ratio=0.12)
    result = calculate_deviation(detection, "full_body")
    assert result.x_deviation < 0


def test_right_of_center_has_positive_x_deviation():
    detection = DetectionSignal(center_x=0.7, area_ratio=0.12)
    result = calculate_deviation(detection, "full_body")
    assert result.x_deviation > 0


def test_small_area_has_negative_size_deviation_for_closeup():
    detection = DetectionSignal(center_x=0.5, area_ratio=0.05)
    result = calculate_deviation(detection, "closeup")
    assert result.size_deviation < 0


def test_large_area_has_positive_size_deviation_for_wide():
    detection = DetectionSignal(center_x=0.5, area_ratio=0.5)
    result = calculate_deviation(detection, "wide")
    assert result.size_deviation > 0


def test_no_detection_returns_none_deviations():
    result = calculate_deviation(None, "full_body")
    assert result.subject_detected is False
    assert result.x_deviation is None
    assert result.size_deviation is None


def test_invalid_framing_raises_value_error():
    detection = DetectionSignal(center_x=0.5, area_ratio=0.12)
    with pytest.raises(ValueError):
        calculate_deviation(detection, "portrait")


def test_invalid_framing_raises_even_when_detection_is_none():
    with pytest.raises(ValueError):
        calculate_deviation(None, "portrait")
