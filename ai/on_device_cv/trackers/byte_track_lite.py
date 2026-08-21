"""A dependency-light, ByteTrack-style multi-object tracker.

This is intentionally implemented with NumPy only so the association semantics
can be ported to Kotlin. It uses constant-velocity box prediction, optimal IoU
assignment, and ByteTrack's high/low-confidence two-stage association. It is not
a drop-in copy of the upstream ByteTrack Kalman-filter implementation.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass, field

import numpy as np

from ai.on_device_cv.contracts import BoundingBox, DetectionResult, TrackedObject


@dataclass(frozen=True, slots=True)
class ByteTrackLiteConfig:
    """Association and lifecycle thresholds for :class:`ByteTrackLiteTracker`."""

    track_activation_threshold: float = 0.25
    minimum_matching_confidence: float = 0.10
    first_match_iou_threshold: float = 0.30
    second_match_iou_threshold: float = 0.20
    lost_track_buffer: int = 30
    velocity_momentum: float = 0.40
    class_aware: bool = True
    label_mismatch_penalty: float = 0.80
    label_vote_decay: float = 0.98
    label_switch_margin: float = 1.50
    # None 이 아니면 track 만료를 프레임 수(lost_track_buffer) 대신 시간(초)으로 판정한다.
    # 기기별 분석 FPS 가 달라도 실제 유지 시간이 같아진다 (기능 1-D).
    lost_track_buffer_seconds: float | None = None
    # 매칭 시 track 예측 bbox 를 마지막 관측 후 경과 시간에 비례해 확장하는 비율(초당,
    # 변 길이 대비). 0.0 = 확장 없음 (기존 동작과 동일). buffered IoU.
    match_expansion_rate_per_second: float = 0.0
    max_match_expansion: float = 0.5

    def __post_init__(self) -> None:
        probability_fields = {
            "track_activation_threshold": self.track_activation_threshold,
            "minimum_matching_confidence": self.minimum_matching_confidence,
            "first_match_iou_threshold": self.first_match_iou_threshold,
            "second_match_iou_threshold": self.second_match_iou_threshold,
            "velocity_momentum": self.velocity_momentum,
            "label_mismatch_penalty": self.label_mismatch_penalty,
            "label_vote_decay": self.label_vote_decay,
        }
        for name, value in probability_fields.items():
            if not math.isfinite(value) or not 0.0 <= value <= 1.0:
                raise ValueError(f"{name} must be in [0, 1]")
        if self.minimum_matching_confidence > self.track_activation_threshold:
            raise ValueError("minimum_matching_confidence cannot exceed track_activation_threshold")
        if self.lost_track_buffer < 0:
            raise ValueError("lost_track_buffer must be non-negative")
        if not math.isfinite(self.label_switch_margin) or self.label_switch_margin < 1.0:
            raise ValueError("label_switch_margin must be finite and at least 1")
        if self.lost_track_buffer_seconds is not None and (
            not math.isfinite(self.lost_track_buffer_seconds)
            or self.lost_track_buffer_seconds <= 0.0
        ):
            raise ValueError("lost_track_buffer_seconds must be None or a positive finite number")
        if not math.isfinite(self.match_expansion_rate_per_second) or (
            self.match_expansion_rate_per_second < 0.0
        ):
            raise ValueError("match_expansion_rate_per_second must be finite and non-negative")
        if not math.isfinite(self.max_match_expansion) or not 0.0 <= self.max_match_expansion <= 2.0:
            raise ValueError("max_match_expansion must be in [0, 2]")


@dataclass(slots=True)
class _Track:
    track_id: int
    state: np.ndarray
    velocity: np.ndarray
    last_observation: np.ndarray
    last_observed_time: float
    label: str
    class_id: int | None
    label_votes: dict[str, float] = field(default_factory=dict)
    missed_frames: int = 0
    age: int = 1
    hits: int = 1

    @classmethod
    def create(cls, track_id: int, detection: DetectionResult, current_time: float) -> _Track:
        state = _bbox_to_state(detection.bbox)
        return cls(
            track_id=track_id,
            state=state,
            velocity=np.zeros(4, dtype=np.float64),
            last_observation=state.copy(),
            last_observed_time=current_time,
            label=detection.label,
            class_id=detection.class_id,
            label_votes={detection.label: max(detection.confidence, 1e-6)},
        )

    @property
    def bbox(self) -> BoundingBox:
        return _state_to_bbox(self.state)

    def predict(self, elapsed_time: float) -> None:
        self.state = _sanitize_state(self.state + self.velocity * elapsed_time)
        self.age += 1

    def apply_motion(self, dx: float, dy: float) -> None:
        """전역(카메라 기인) 이동 보정 — 예측 중심만 이동, 속도 추정에는 반영하지 않는다."""
        shifted = self.state.copy()
        shifted[0] += dx
        shifted[1] += dy
        self.state = _sanitize_state(shifted)

    def match_expansion(self, frame_time: float, config: ByteTrackLiteConfig) -> float:
        """매칭 시 적용할 확장 비율 (buffered IoU, 기능 1-D) — 마지막 관측 후 경과 시간 비례.

        track 예측 bbox 와 검출 bbox **양쪽에 같은 비율**로 적용해야 union 증가로 IoU 가
        희석되지 않는다 (BIoU 방식). 0.0 = 확장 없음.
        """
        if config.match_expansion_rate_per_second <= 0.0:
            return 0.0
        elapsed = max(0.0, frame_time - self.last_observed_time)
        return min(config.max_match_expansion, config.match_expansion_rate_per_second * elapsed)

    def update(
        self,
        detection: DetectionResult,
        current_time: float,
        config: ByteTrackLiteConfig,
    ) -> None:
        observed_state = _bbox_to_state(detection.bbox)
        elapsed_time = max(1e-9, current_time - self.last_observed_time)
        measured_velocity = (observed_state - self.last_observation) / elapsed_time
        self.velocity = (
            config.velocity_momentum * self.velocity
            + (1.0 - config.velocity_momentum) * measured_velocity
        )
        self.state = observed_state
        self.last_observation = observed_state.copy()
        self.last_observed_time = current_time
        self.missed_frames = 0
        self.hits += 1
        if self.class_id is None and detection.class_id is not None:
            self.class_id = detection.class_id

        for label in tuple(self.label_votes):
            self.label_votes[label] *= config.label_vote_decay
            if self.label_votes[label] < 1e-8:
                del self.label_votes[label]
        self.label_votes[detection.label] = (
            self.label_votes.get(detection.label, 0.0) + detection.confidence
        )
        challenger = min(
            self.label_votes,
            key=lambda label: (-self.label_votes[label], label),
        )
        if challenger == self.label:
            return
        current_vote = self.label_votes.get(self.label, 0.0)
        if self.label_votes[challenger] >= current_vote * config.label_switch_margin:
            self.label = challenger

    def as_observed(self, detection: DetectionResult) -> TrackedObject:
        return TrackedObject(
            track_id=self.track_id,
            # Confidence is the detector's score for its current-frame label.
            # Keep this pair semantically intact; the voted label is internal
            # association state only.
            label=detection.label,
            confidence=detection.confidence,
            bbox=detection.bbox,
            class_id=detection.class_id,
        )


class ByteTrackLiteTracker:
    """Track multiple objects using motion-aware, two-stage IoU assignment.

    Low-confidence detections can recover an existing track but never create a
    new ID. Tracks survive internally for ``lost_track_buffer`` missing frames;
    predicted-only tracks are deliberately not returned to callers.
    """

    def __init__(self, config: ByteTrackLiteConfig | None = None) -> None:
        self.config = config or ByteTrackLiteConfig()
        self._tracks: dict[int, _Track] = {}
        self._next_track_id = 1
        self._frame_index = 0
        self._current_time = 0.0
        self._last_external_timestamp: float | None = None
        self._uses_external_timestamps: bool | None = None

    def update(
        self,
        detections: Sequence[DetectionResult],
        *,
        timestamp_s: float | None = None,
        motion_hint: tuple[float, float] | None = None,
    ) -> list[TrackedObject]:
        candidates = []
        for detection in detections:
            if not isinstance(detection, DetectionResult):
                raise TypeError("detections must contain DetectionResult values")
            if detection.confidence >= self.config.minimum_matching_confidence:
                candidates.append(detection)
        candidates.sort(key=_detection_sort_key)
        current_time = self._validated_time(timestamp_s)
        elapsed_time = current_time - self._current_time if self._frame_index else 1.0
        self._frame_index += 1
        self._current_time = current_time
        if timestamp_s is not None:
            self._last_external_timestamp = timestamp_s

        high_confidence = [
            detection
            for detection in candidates
            if detection.confidence >= self.config.track_activation_threshold
        ]
        low_confidence = [
            detection
            for detection in candidates
            if detection.confidence < self.config.track_activation_threshold
        ]

        active_tracks = sorted(self._tracks.values(), key=lambda track: track.track_id)
        for track in active_tracks:
            track.predict(elapsed_time)
        # 카메라 이동 보정 (기능 1-C): 전역 이동량을 예측 위치에 더해 IoU 매칭을 살린다.
        if motion_hint is not None and motion_hint != (0.0, 0.0):
            hint_dx, hint_dy = motion_hint
            if not (math.isfinite(hint_dx) and math.isfinite(hint_dy)):
                raise ValueError("motion_hint must be finite")
            for track in active_tracks:
                track.apply_motion(hint_dx, hint_dy)

        first_matches, unmatched_tracks, unmatched_high = self._associate(
            active_tracks,
            high_confidence,
            self.config.first_match_iou_threshold,
            current_time,
        )
        # ByteTrack's low-confidence stage is only for tracks observed in the
        # immediately preceding frame. A low-confidence false positive must not
        # revive an already-lost track or extend its buffer indefinitely.
        low_confidence_eligible_tracks = [
            track for track in unmatched_tracks if track.missed_frames == 0
        ]
        second_matches, _, _ = self._associate(
            low_confidence_eligible_tracks,
            low_confidence,
            self.config.second_match_iou_threshold,
            current_time,
        )

        observed: list[TrackedObject] = []
        matched_track_ids: set[int] = set()
        for track, detection in (*first_matches, *second_matches):
            track.update(detection, current_time, self.config)
            matched_track_ids.add(track.track_id)
            observed.append(track.as_observed(detection))

        for track in active_tracks:
            if track.track_id not in matched_track_ids:
                track.missed_frames += 1

        for detection in unmatched_high:
            track = _Track.create(self._next_track_id, detection, current_time)
            self._tracks[track.track_id] = track
            self._next_track_id += 1
            observed.append(track.as_observed(detection))

        # 만료: 시간 기준 옵션이 켜져 있으면 프레임 수 대신 마지막 관측 후 경과 시간으로 판정 (기능 1-D)
        buffer_seconds = self.config.lost_track_buffer_seconds
        if buffer_seconds is not None:
            expired_ids = [
                track_id
                for track_id, track in self._tracks.items()
                if track.missed_frames > 0
                and current_time - track.last_observed_time > buffer_seconds
            ]
        else:
            expired_ids = [
                track_id
                for track_id, track in self._tracks.items()
                if track.missed_frames > self.config.lost_track_buffer
            ]
        for track_id in expired_ids:
            del self._tracks[track_id]

        observed.sort(key=lambda item: item.track_id)
        return observed

    def reset(self) -> None:
        self._tracks.clear()
        self._next_track_id = 1
        self._frame_index = 0
        self._current_time = 0.0
        self._last_external_timestamp = None
        self._uses_external_timestamps = None

    def _validated_time(self, timestamp_s: float | None) -> float:
        uses_external_timestamp = timestamp_s is not None
        if self._uses_external_timestamps is not None and (
            uses_external_timestamp != self._uses_external_timestamps
        ):
            raise ValueError("Use timestamps for every frame in a stream or for none of them")

        if timestamp_s is None:
            current_time = self._current_time + 1.0
        else:
            if not math.isfinite(timestamp_s):
                raise ValueError("timestamp_s must be finite")
            if (
                self._last_external_timestamp is not None
                and timestamp_s <= self._last_external_timestamp
            ):
                raise ValueError("timestamp_s must increase strictly within a stream")
            current_time = float(timestamp_s)

        if self._uses_external_timestamps is None:
            self._uses_external_timestamps = uses_external_timestamp
        return current_time

    def _associate(
        self,
        tracks: Sequence[_Track],
        detections: Sequence[DetectionResult],
        minimum_iou: float,
        current_time: float,
    ) -> tuple[
        list[tuple[_Track, DetectionResult]],
        list[_Track],
        list[DetectionResult],
    ]:
        if not tracks or not detections:
            return [], list(tracks), list(detections)

        # buffered IoU (기능 1-D): 놓친 시간에 비례해 track·검출 bbox 를 같은 비율로 확장
        expansions = np.array(
            [track.match_expansion(current_time, self.config) for track in tracks],
            dtype=np.float64,
        )
        track_boxes = np.array(
            [
                [track.bbox.x_min, track.bbox.y_min, track.bbox.x_max, track.bbox.y_max]
                for track in tracks
            ],
            dtype=np.float64,
        )
        detection_boxes = np.array(
            [
                [
                    detection.bbox.x_min,
                    detection.bbox.y_min,
                    detection.bbox.x_max,
                    detection.bbox.y_max,
                ]
                for detection in detections
            ],
            dtype=np.float64,
        )
        if expansions.any():
            scores = _pairwise_buffered_iou(track_boxes, detection_boxes, expansions)
        else:
            scores = _pairwise_iou(track_boxes, detection_boxes)
        valid = (scores > 0.0) & (scores >= minimum_iou)
        if self.config.class_aware:
            mismatch = np.array(
                [
                    [_class_identity_mismatch(track, detection) for detection in detections]
                    for track in tracks
                ],
                dtype=bool,
            )
            scores[mismatch] *= self.config.label_mismatch_penalty
            valid &= scores > 0.0

        matched_track_indices: set[int] = set()
        matched_detection_indices: set[int] = set()
        matches: list[tuple[_Track, DetectionResult]] = []
        for component_tracks, component_detections in _association_components(valid):
            component_scores = scores[np.ix_(component_tracks, component_detections)]
            component_valid = valid[np.ix_(component_tracks, component_detections)]
            local_track_count = len(component_tracks)
            local_detection_count = len(component_detections)

            # One zero-cost dummy column per track lets the optimizer leave any
            # track unmatched instead of forcing an invalid real assignment.
            costs = np.zeros(
                (local_track_count, local_detection_count + local_track_count),
                dtype=np.float64,
            )
            costs[:, :local_detection_count] = 1e6
            costs[:, :local_detection_count][component_valid] = -component_scores[component_valid]
            row_indices, column_indices = _linear_sum_assignment(costs)

            for local_track, local_detection in zip(row_indices, column_indices, strict=True):
                if (
                    local_detection >= local_detection_count
                    or not component_valid[local_track, local_detection]
                ):
                    continue
                track_index = component_tracks[local_track]
                detection_index = component_detections[local_detection]
                matches.append((tracks[track_index], detections[detection_index]))
                matched_track_indices.add(track_index)
                matched_detection_indices.add(detection_index)

        unmatched_tracks = [
            track for index, track in enumerate(tracks) if index not in matched_track_indices
        ]
        unmatched_detections = [
            detection
            for index, detection in enumerate(detections)
            if index not in matched_detection_indices
        ]
        return matches, unmatched_tracks, unmatched_detections


def _bbox_to_state(bbox: BoundingBox) -> np.ndarray:
    width = bbox.x_max - bbox.x_min
    height = bbox.y_max - bbox.y_min
    return np.array(
        [bbox.x_min + width / 2.0, bbox.y_min + height / 2.0, width, height],
        dtype=np.float64,
    )


def _sanitize_state(state: np.ndarray) -> np.ndarray:
    result = np.asarray(state, dtype=np.float64).copy()
    result[2] = min(1.0, max(1e-6, result[2]))
    result[3] = min(1.0, max(1e-6, result[3]))
    result[0] = min(1.0 - result[2] / 2.0, max(result[2] / 2.0, result[0]))
    result[1] = min(1.0 - result[3] / 2.0, max(result[3] / 2.0, result[1]))
    return result


def _state_to_bbox(state: np.ndarray) -> BoundingBox:
    center_x, center_y, width, height = _sanitize_state(state)
    return BoundingBox(
        x_min=float(center_x - width / 2.0),
        y_min=float(center_y - height / 2.0),
        x_max=float(center_x + width / 2.0),
        y_max=float(center_y + height / 2.0),
    )


def _detection_sort_key(detection: DetectionResult) -> tuple[float | str | int, ...]:
    bbox = detection.bbox
    return (
        round(bbox.x_min, 12),
        round(bbox.y_min, 12),
        round(bbox.x_max, 12),
        round(bbox.y_max, 12),
        detection.label,
        -round(detection.confidence, 12),
        detection.class_id if detection.class_id is not None else -1,
    )


def _class_identity_mismatch(track: _Track, detection: DetectionResult) -> bool:
    if track.class_id is not None and detection.class_id is not None:
        return track.class_id != detection.class_id
    return track.label.strip().casefold() != detection.label.strip().casefold()


def _pairwise_iou(track_boxes: np.ndarray, detection_boxes: np.ndarray) -> np.ndarray:
    intersection_min = np.maximum(track_boxes[:, None, :2], detection_boxes[None, :, :2])
    intersection_max = np.minimum(track_boxes[:, None, 2:], detection_boxes[None, :, 2:])
    intersection_size = np.maximum(0.0, intersection_max - intersection_min)
    intersection_area = intersection_size[..., 0] * intersection_size[..., 1]
    track_area = (
        (track_boxes[:, 2] - track_boxes[:, 0]) * (track_boxes[:, 3] - track_boxes[:, 1])
    )[:, None]
    detection_area = (
        (detection_boxes[:, 2] - detection_boxes[:, 0])
        * (detection_boxes[:, 3] - detection_boxes[:, 1])
    )[None, :]
    union = track_area + detection_area - intersection_area
    return np.divide(
        intersection_area,
        union,
        out=np.zeros_like(intersection_area),
        where=union > 0.0,
    )


def _expand_boxes(boxes: np.ndarray, fractions: np.ndarray) -> np.ndarray:
    """각 행의 bbox 를 행별 비율만큼 대칭 확장한다. boxes (N,4), fractions broadcast 가능."""
    widths = boxes[..., 2] - boxes[..., 0]
    heights = boxes[..., 3] - boxes[..., 1]
    expanded = np.empty_like(boxes)
    expanded[..., 0] = np.clip(boxes[..., 0] - fractions * widths, 0.0, 1.0)
    expanded[..., 1] = np.clip(boxes[..., 1] - fractions * heights, 0.0, 1.0)
    expanded[..., 2] = np.clip(boxes[..., 2] + fractions * widths, 0.0, 1.0)
    expanded[..., 3] = np.clip(boxes[..., 3] + fractions * heights, 0.0, 1.0)
    return expanded


def _pairwise_buffered_iou(
    track_boxes: np.ndarray,
    detection_boxes: np.ndarray,
    expansions: np.ndarray,
) -> np.ndarray:
    """track 별 확장 비율을 track·검출 양쪽에 적용한 buffered IoU (BIoU) 행렬 (T, D)."""
    expanded_tracks = _expand_boxes(track_boxes, expansions[:, None])  # (T, 4)
    # 검출 bbox 는 track 행마다 다른 비율로 확장된다 → (T, D, 4)
    tiled_detections = np.broadcast_to(
        detection_boxes[None, :, :], (track_boxes.shape[0], *detection_boxes.shape)
    )
    expanded_detections = _expand_boxes(tiled_detections, expansions[:, None, None])
    intersection_min = np.maximum(expanded_tracks[:, None, :2], expanded_detections[..., :2])
    intersection_max = np.minimum(expanded_tracks[:, None, 2:], expanded_detections[..., 2:])
    intersection_size = np.maximum(0.0, intersection_max - intersection_min)
    intersection_area = intersection_size[..., 0] * intersection_size[..., 1]
    track_area = (
        (expanded_tracks[:, 2] - expanded_tracks[:, 0])
        * (expanded_tracks[:, 3] - expanded_tracks[:, 1])
    )[:, None]
    detection_area = (
        expanded_detections[..., 2] - expanded_detections[..., 0]
    ) * (expanded_detections[..., 3] - expanded_detections[..., 1])
    union = track_area + detection_area - intersection_area
    return np.divide(
        intersection_area,
        union,
        out=np.zeros_like(intersection_area),
        where=union > 0.0,
    )


def _association_components(valid: np.ndarray) -> list[tuple[list[int], list[int]]]:
    """Split a sparse bipartite match graph into independent exact subproblems."""

    track_count, detection_count = valid.shape
    seen_tracks = np.zeros(track_count, dtype=bool)
    seen_detections = np.zeros(detection_count, dtype=bool)
    components: list[tuple[list[int], list[int]]] = []

    for starting_track in range(track_count):
        if seen_tracks[starting_track] or not valid[starting_track].any():
            continue
        component_tracks: list[int] = []
        component_detections: list[int] = []
        track_stack = [starting_track]
        seen_tracks[starting_track] = True

        while track_stack:
            track_index = track_stack.pop()
            component_tracks.append(track_index)
            new_detections = np.flatnonzero(valid[track_index] & ~seen_detections)
            for detection_index in new_detections.tolist():
                seen_detections[detection_index] = True
                component_detections.append(detection_index)
                new_tracks = np.flatnonzero(valid[:, detection_index] & ~seen_tracks)
                for new_track in new_tracks.tolist():
                    seen_tracks[new_track] = True
                    track_stack.append(new_track)

        component_tracks.sort()
        component_detections.sort()
        components.append((component_tracks, component_detections))
    return components


def _linear_sum_assignment(cost_matrix: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Solve rectangular minimum-cost assignment without a SciPy dependency.

    This shortest-augmenting-path implementation expects at least as many
    columns as rows. The tracker always satisfies that by appending dummy
    unmatched columns.
    """

    costs = np.asarray(cost_matrix, dtype=np.float64)
    if costs.ndim != 2:
        raise ValueError("cost_matrix must be two-dimensional")
    row_count, column_count = costs.shape
    if row_count == 0:
        return np.empty(0, dtype=np.int64), np.empty(0, dtype=np.int64)
    if row_count > column_count:
        raise ValueError("cost_matrix must have at least as many columns as rows")
    if not np.isfinite(costs).all():
        raise ValueError("cost_matrix must contain only finite values")

    row_potential = np.zeros(row_count + 1, dtype=np.float64)
    column_potential = np.zeros(column_count + 1, dtype=np.float64)
    column_match = np.zeros(column_count + 1, dtype=np.int64)
    predecessor = np.zeros(column_count + 1, dtype=np.int64)
    epsilon = 1e-12

    for row in range(1, row_count + 1):
        column_match[0] = row
        current_column = 0
        minimum_values = np.full(column_count + 1, np.inf, dtype=np.float64)
        used = np.zeros(column_count + 1, dtype=bool)

        while True:
            used[current_column] = True
            current_row = column_match[current_column]
            delta = np.inf
            next_column = 0
            for column in range(1, column_count + 1):
                if used[column]:
                    continue
                reduced_cost = (
                    costs[current_row - 1, column - 1]
                    - row_potential[current_row]
                    - column_potential[column]
                )
                if reduced_cost < minimum_values[column] - epsilon:
                    minimum_values[column] = reduced_cost
                    predecessor[column] = current_column
                if minimum_values[column] < delta - epsilon or (
                    abs(minimum_values[column] - delta) <= epsilon
                    and (next_column == 0 or column < next_column)
                ):
                    delta = minimum_values[column]
                    next_column = column

            for column in range(column_count + 1):
                if used[column]:
                    row_potential[column_match[column]] += delta
                    column_potential[column] -= delta
                else:
                    minimum_values[column] -= delta
            current_column = next_column
            if column_match[current_column] == 0:
                break

        while True:
            previous_column = predecessor[current_column]
            column_match[current_column] = column_match[previous_column]
            current_column = previous_column
            if current_column == 0:
                break

    assigned_columns = np.empty(row_count, dtype=np.int64)
    for column in range(1, column_count + 1):
        matched_row = column_match[column]
        if matched_row != 0:
            assigned_columns[matched_row - 1] = column - 1
    return np.arange(row_count, dtype=np.int64), assigned_columns
