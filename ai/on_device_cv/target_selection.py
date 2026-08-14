"""TargetSpec-based selection applied after all-object tracking."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Any

from ai.on_device_cv.contracts import FrameResult, TrackedObject
from ai.target_spec import SubjectType, TargetSpec, TargetSpecStatus
from ai.taxonomy import OBJECTS365_YOLO26, ObjectTaxonomy


class TargetSelectionState(StrEnum):
    SELECTED = "selected"
    SEARCHING = "searching"
    AMBIGUOUS = "ambiguous"
    SCENE_ONLY = "scene_only"
    UNRESOLVED = "unresolved"


class TargetCountStatus(StrEnum):
    NOT_REQUESTED = "not_requested"
    UNDER = "under"
    EXACT = "exact"
    OVER = "over"
    NOT_APPLICABLE = "not_applicable"


@dataclass(frozen=True, slots=True)
class TargetSelectionResult:
    """Target candidates plus enough state to avoid silently choosing the wrong object."""

    session_id: str
    state: TargetSelectionState
    subject_type: SubjectType
    candidates: tuple[TrackedObject, ...]
    requested_count: int | None
    count_status: TargetCountStatus
    framing: str
    schema_version: str = "0.1"
    object_label: str | None = None

    @property
    def detected_count(self) -> int:
        return len(self.candidates)

    def to_frame_result(self) -> FrameResult:
        """Return candidates using the existing stable per-frame object schema."""

        return FrameResult.from_objects(self.candidates)

    def to_dict(self) -> dict[str, Any]:
        """Serialize the additive TargetSpec selection contract."""

        payload = {
            "schemaVersion": self.schema_version,
            "sessionId": self.session_id,
            "state": self.state.value,
            "subjectType": self.subject_type.value,
            "requestedCount": self.requested_count,
            "detectedCount": self.detected_count,
            "countStatus": self.count_status.value,
            "framing": self.framing,
            "objects": [candidate.to_dict() for candidate in self.candidates],
        }
        if self.schema_version == "0.2":
            payload["objectLabel"] = self.object_label
        return payload


@dataclass(frozen=True, slots=True)
class TargetSelectorConfig:
    """Taxonomy used by the detector that produced the tracked objects."""

    taxonomy: ObjectTaxonomy = OBJECTS365_YOLO26
    person_class_id: int | None = None
    person_labels: frozenset[str] = frozenset({"person"})

    def __post_init__(self) -> None:
        if not isinstance(self.taxonomy, ObjectTaxonomy):
            raise TypeError("taxonomy must be an ObjectTaxonomy")
        if self.person_class_id is not None and (
            type(self.person_class_id) is not int or self.person_class_id < 0
        ):
            raise ValueError("person_class_id must be null or a non-negative integer")
        normalized_labels = frozenset(label.strip().casefold() for label in self.person_labels)
        if not normalized_labels or "" in normalized_labels:
            raise ValueError("person_labels must contain non-empty labels")
        object.__setattr__(self, "person_labels", normalized_labels)


class TargetSelector:
    """Select intent-matching candidates without altering detector/tracker state."""

    def __init__(self, config: TargetSelectorConfig | None = None) -> None:
        self.config = config or TargetSelectorConfig()

    def select(self, frame_result: FrameResult, target_spec: TargetSpec) -> TargetSelectionResult:
        if target_spec.status is not TargetSpecStatus.OK:
            return self._result(
                target_spec,
                TargetSelectionState.UNRESOLVED,
                (),
                TargetCountStatus.NOT_APPLICABLE,
            )

        if target_spec.subject_type is SubjectType.LANDSCAPE:
            return self._result(
                target_spec,
                TargetSelectionState.SCENE_ONLY,
                (),
                TargetCountStatus.NOT_APPLICABLE,
            )

        if target_spec.subject_type is SubjectType.PERSON:
            candidates = tuple(item for item in frame_result.objects if self._is_person(item))
        else:
            candidates = tuple(
                item
                for item in frame_result.objects
                if self.config.taxonomy.is_supported_object(
                    class_id=item.class_id,
                    observed_label=item.label,
                )
            )
            if target_spec.object_label is not None:
                candidates = tuple(
                    item
                    for item in candidates
                    if self.config.taxonomy.matches(
                        class_id=item.class_id,
                        observed_label=item.label,
                        canonical_label=target_spec.object_label,
                    )
                )
        candidates = tuple(sorted(candidates, key=lambda item: item.track_id))

        requested_count = target_spec.subject_count
        if requested_count is None:
            count_status = TargetCountStatus.NOT_REQUESTED
            state = TargetSelectionState.SELECTED if candidates else TargetSelectionState.SEARCHING
        elif len(candidates) < requested_count:
            count_status = TargetCountStatus.UNDER
            state = TargetSelectionState.SEARCHING
        elif len(candidates) == requested_count:
            count_status = TargetCountStatus.EXACT
            state = TargetSelectionState.SELECTED
        else:
            # There is no identity qualifier in TargetSpec, so choosing
            # an arbitrary top-N subset would be unsafe.
            count_status = TargetCountStatus.OVER
            state = TargetSelectionState.AMBIGUOUS

        return self._result(target_spec, state, candidates, count_status)

    def _is_person(self, item: TrackedObject) -> bool:
        if self.config.person_class_id is not None and item.class_id is not None:
            return item.class_id == self.config.person_class_id
        if item.class_id is None and item.label.strip().casefold() in self.config.person_labels:
            return True
        return self.config.taxonomy.matches(
            class_id=item.class_id,
            observed_label=item.label,
            canonical_label="person",
        )

    @staticmethod
    def _result(
        target_spec: TargetSpec,
        state: TargetSelectionState,
        candidates: tuple[TrackedObject, ...],
        count_status: TargetCountStatus,
    ) -> TargetSelectionResult:
        return TargetSelectionResult(
            schema_version=target_spec.schema_version,
            session_id=target_spec.session_id,
            state=state,
            subject_type=target_spec.subject_type,
            object_label=target_spec.object_label,
            candidates=candidates,
            requested_count=target_spec.subject_count,
            count_status=count_status,
            framing=target_spec.framing.value,
        )
