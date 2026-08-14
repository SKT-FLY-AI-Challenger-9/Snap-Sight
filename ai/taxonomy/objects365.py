"""Objects365 taxonomy for the deployed YOLO26 Objects365 checkpoint."""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType


@dataclass(frozen=True, slots=True)
class ObjectTaxonomy:
    """Immutable mapping between model output indices and canonical labels."""

    taxonomy_id: str
    model_id: str
    model_sha256: str
    labels: tuple[str, ...]
    _class_ids: Mapping[str, int] = field(init=False, repr=False, compare=False)
    _normalized_class_ids: Mapping[str, int] = field(init=False, repr=False, compare=False)

    def __post_init__(self) -> None:
        if not self.taxonomy_id.strip():
            raise ValueError("taxonomyId must not be empty")
        if not self.model_id.strip():
            raise ValueError("modelId must not be empty")
        if len(self.model_sha256) != 64 or any(
            character not in "0123456789abcdef" for character in self.model_sha256
        ):
            raise ValueError("modelSha256 must be a lowercase SHA-256 hex digest")
        if not self.labels:
            raise ValueError("taxonomy labels must not be empty")
        if any(
            not isinstance(label, str) or not label or label != label.strip()
            for label in self.labels
        ):
            raise ValueError("taxonomy labels must be non-empty trimmed strings")

        normalized = [label.casefold() for label in self.labels]
        if len(normalized) != len(set(normalized)):
            raise ValueError("taxonomy labels must be unique ignoring case")
        object.__setattr__(
            self,
            "_class_ids",
            MappingProxyType({label: class_id for class_id, label in enumerate(self.labels)}),
        )
        object.__setattr__(
            self,
            "_normalized_class_ids",
            MappingProxyType(
                {label.casefold(): class_id for class_id, label in enumerate(self.labels)}
            ),
        )

    @property
    def class_count(self) -> int:
        return len(self.labels)

    @property
    def person_class_id(self) -> int:
        return self.class_id_for_label("person")

    @property
    def object_labels(self) -> tuple[str, ...]:
        """Labels valid for TargetSpec subjectType=object."""

        return tuple(label for label in self.labels if label != "person")

    def class_id_for_label(self, label: str) -> int:
        try:
            return self._class_ids[label]
        except KeyError as exc:
            raise ValueError(f"Unknown {self.taxonomy_id} label: {label!r}") from exc

    def label_for_class_id(self, class_id: int) -> str:
        if type(class_id) is not int or not 0 <= class_id < self.class_count:
            raise ValueError(f"class_id must be an integer in [0, {self.class_count - 1}]")
        return self.labels[class_id]

    def is_object_label(self, label: str) -> bool:
        return label != "person" and label in self._class_ids

    def class_id_for_observation(
        self,
        *,
        class_id: int | None,
        observed_label: str,
    ) -> int | None:
        if class_id is not None:
            return class_id if 0 <= class_id < self.class_count else None
        return self._normalized_class_ids.get(observed_label.strip().casefold())

    def is_supported_object(self, *, class_id: int | None, observed_label: str) -> bool:
        observed_class_id = self.class_id_for_observation(
            class_id=class_id,
            observed_label=observed_label,
        )
        return observed_class_id is not None and observed_class_id != self.person_class_id

    def matches(
        self,
        *,
        class_id: int | None,
        observed_label: str,
        canonical_label: str,
    ) -> bool:
        """Match by stable model class ID, with a label fallback for legacy extensions."""

        expected_class_id = self._class_ids.get(canonical_label)
        if expected_class_id is None:
            return False
        if class_id is not None:
            return class_id == expected_class_id
        return observed_label.strip().casefold() == canonical_label.casefold()

    def validate_model_names(self, names: Mapping[int, str] | Sequence[str]) -> None:
        """Fail fast when a detector's output indices do not match this taxonomy."""

        try:
            observed = (
                tuple(names[index] for index in range(len(names)))
                if isinstance(names, Mapping)
                else tuple(names)
            )
        except (KeyError, TypeError) as exc:
            raise ValueError("model names must provide consecutive class IDs from zero") from exc
        if observed == self.labels:
            return

        mismatch_index = next(
            (
                index
                for index, (expected, actual) in enumerate(zip(self.labels, observed, strict=False))
                if expected != actual
            ),
            min(len(self.labels), len(observed)),
        )
        expected = self.labels[mismatch_index] if mismatch_index < len(self.labels) else "<end>"
        actual = observed[mismatch_index] if mismatch_index < len(observed) else "<end>"
        raise ValueError(
            f"Model taxonomy mismatch at class {mismatch_index}: "
            f"expected {expected!r}, got {actual!r}"
        )


def _load_objects365_taxonomy(path: Path) -> ObjectTaxonomy:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"Could not load Objects365 taxonomy {path}: {exc}") from exc

    if not isinstance(payload, dict):
        raise TypeError("Objects365 taxonomy must be a JSON object")
    labels = payload.get("labels")
    if not isinstance(labels, list) or not all(isinstance(label, str) for label in labels):
        raise RuntimeError("Objects365 taxonomy labels must be a string array")
    class_count = payload.get("classCount")
    if type(class_count) is not int or class_count != len(labels):
        raise RuntimeError("Objects365 taxonomy classCount must match labels length")
    if payload.get("schemaVersion") != "1.0":
        raise RuntimeError("Unsupported Objects365 taxonomy schemaVersion")

    required_strings = ("taxonomyId", "modelId", "modelSha256")
    if any(not isinstance(payload.get(name), str) for name in required_strings):
        raise RuntimeError("Objects365 taxonomy metadata fields must be strings")
    return ObjectTaxonomy(
        taxonomy_id=payload["taxonomyId"],
        model_id=payload["modelId"],
        model_sha256=payload["modelSha256"],
        labels=tuple(labels),
    )


OBJECTS365_YOLO26 = _load_objects365_taxonomy(Path(__file__).with_name("objects365_yolo26_v1.json"))
