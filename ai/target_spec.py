"""Validated TargetSpec contract shared with the on-device CV selector."""

from __future__ import annotations

import json
import math
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Any, TypeVar

EnumValue = TypeVar("EnumValue", bound=StrEnum)


class TargetSpecStatus(StrEnum):
    OK = "ok"
    NEEDS_CLARIFICATION = "needs_clarification"
    FAILED = "failed"


class SubjectType(StrEnum):
    PERSON = "person"
    OBJECT = "object"
    LANDSCAPE = "landscape"


class Framing(StrEnum):
    CLOSEUP = "closeup"
    FULL_BODY = "full_body"
    WIDE = "wide"


class TargetSpecSource(StrEnum):
    CLOVA = "clova"
    ELEVENLABS = "elevenlabs"
    ONDEVICE = "ondevice"


@dataclass(frozen=True, slots=True)
class TargetSpec:
    """Machine-validated representation of ``ai/target_spec_schema.md`` v0.1."""

    session_id: str
    raw_text: str
    source: TargetSpecSource
    schema_version: str = "0.1"
    status: TargetSpecStatus = TargetSpecStatus.OK
    subject_type: SubjectType = SubjectType.PERSON
    subject_count: int | None = None
    framing: Framing = Framing.FULL_BODY
    confidence: float = 0.0

    def __post_init__(self) -> None:
        if self.schema_version != "0.1":
            raise ValueError(f"Unsupported TargetSpec schemaVersion: {self.schema_version}")
        if not isinstance(self.session_id, str):
            raise TypeError("TargetSpec sessionId must be a string")
        if not self.session_id.strip():
            raise ValueError("TargetSpec sessionId must be a non-empty string")
        if not isinstance(self.raw_text, str):
            raise TypeError("TargetSpec rawText must be a string")
        enum_fields = {
            "status": (self.status, TargetSpecStatus),
            "subjectType": (self.subject_type, SubjectType),
            "framing": (self.framing, Framing),
            "source": (self.source, TargetSpecSource),
        }
        for field_name, (value, expected_type) in enum_fields.items():
            if not isinstance(value, expected_type):
                raise TypeError(f"TargetSpec {field_name} must use {expected_type.__name__}")
        if self.status is not TargetSpecStatus.FAILED and not self.raw_text.strip():
            raise ValueError("TargetSpec rawText must not be empty unless status is failed")
        if self.subject_count is not None and (
            type(self.subject_count) is not int or self.subject_count < 1
        ):
            raise ValueError("TargetSpec subjectCount must be null or an integer of at least 1")
        if isinstance(self.confidence, bool) or not isinstance(self.confidence, (int, float)):
            raise TypeError("TargetSpec confidence must be a number")
        if not math.isfinite(self.confidence) or not 0.0 <= self.confidence <= 1.0:
            raise ValueError("TargetSpec confidence must be in [0, 1]")

    @classmethod
    def from_dict(cls, payload: Mapping[str, object]) -> TargetSpec:
        if not isinstance(payload, Mapping):
            raise TypeError("TargetSpec must be a JSON object")

        allowed_fields = {
            "schemaVersion",
            "sessionId",
            "status",
            "subjectType",
            "subjectCount",
            "framing",
            "rawText",
            "confidence",
            "source",
        }
        unknown_fields = sorted(set(payload) - allowed_fields)
        if unknown_fields:
            raise ValueError(f"Unknown TargetSpec fields: {', '.join(unknown_fields)}")

        for required_field in ("sessionId", "rawText", "source"):
            if required_field not in payload:
                raise ValueError(f"TargetSpec is missing required field: {required_field}")

        schema_version = _string_field(payload, "schemaVersion", default="0.1")
        session_id = _string_field(payload, "sessionId")
        raw_text = _string_field(payload, "rawText", allow_empty=True)
        subject_count = payload.get("subjectCount")
        if subject_count is not None and (type(subject_count) is not int or subject_count < 1):
            raise ValueError("TargetSpec subjectCount must be null or an integer of at least 1")

        confidence = payload.get("confidence", 0.0)
        if isinstance(confidence, bool) or not isinstance(confidence, (int, float)):
            raise TypeError("TargetSpec confidence must be a number")

        return cls(
            schema_version=schema_version,
            session_id=session_id,
            status=_enum_field(
                TargetSpecStatus,
                payload.get("status", TargetSpecStatus.OK.value),
                "status",
            ),
            subject_type=_enum_field(
                SubjectType,
                payload.get("subjectType", SubjectType.PERSON.value),
                "subjectType",
            ),
            subject_count=subject_count,
            framing=_enum_field(
                Framing,
                payload.get("framing", Framing.FULL_BODY.value),
                "framing",
            ),
            raw_text=raw_text,
            confidence=float(confidence),
            source=_enum_field(TargetSpecSource, payload["source"], "source"),
        )

    @classmethod
    def from_json(cls, value: str) -> TargetSpec:
        try:
            payload = json.loads(value)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid TargetSpec JSON: {exc.msg}") from exc
        return cls.from_dict(payload)

    @classmethod
    def from_file(cls, path: str | Path) -> TargetSpec:
        target_path = Path(path)
        try:
            contents = target_path.read_text(encoding="utf-8")
        except OSError as exc:
            raise ValueError(f"Could not read TargetSpec file {target_path}: {exc}") from exc
        return cls.from_json(contents)

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "sessionId": self.session_id,
            "status": self.status.value,
            "subjectType": self.subject_type.value,
            "subjectCount": self.subject_count,
            "framing": self.framing.value,
            "rawText": self.raw_text,
            "confidence": float(self.confidence),
            "source": self.source.value,
        }


def _string_field(
    payload: Mapping[str, object],
    name: str,
    *,
    default: str | None = None,
    allow_empty: bool = False,
) -> str:
    value = payload.get(name, default)
    if not isinstance(value, str):
        raise TypeError(f"TargetSpec {name} must be a string")
    if not allow_empty and not value.strip():
        raise ValueError(f"TargetSpec {name} must be a non-empty string")
    return value


def _enum_field(enum_type: type[EnumValue], value: object, name: str) -> EnumValue:
    if not isinstance(value, str):
        raise TypeError(f"TargetSpec {name} must be a string")
    try:
        return enum_type(value)
    except ValueError as exc:
        allowed = ", ".join(item.value for item in enum_type)
        raise ValueError(f"TargetSpec {name} must be one of: {allowed}") from exc
