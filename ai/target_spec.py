"""Validated TargetSpec contract shared with the on-device CV selector."""

from __future__ import annotations

import json
import math
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Any, TypeVar

from ai.taxonomy import OBJECTS365_YOLO26

EnumValue = TypeVar("EnumValue", bound=StrEnum)
SUPPORTED_SCHEMA_VERSIONS = frozenset({"0.1", "0.2"})


class TargetSpecStatus(StrEnum):
    OK = "ok"
    NEEDS_CLARIFICATION = "needs_clarification"
    FAILED = "failed"


class SubjectType(StrEnum):
    PERSON = "person"
    OBJECT = "object"
    LANDSCAPE = "landscape"
    # 서류·종이·신분증 (2026-08-30) — bbox 조준 대상이 없고 앱이 텍스트 영역으로 프레이밍한다.
    # 사진은 기기 밖으로 나가지 않는다(앱이 업로드를 생략).
    DOCUMENT = "document"


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
    """Machine-validated representation of ``ai/target_spec_schema.md`` v0.1/v0.2."""

    session_id: str
    raw_text: str
    source: TargetSpecSource
    schema_version: str = "0.1"
    status: TargetSpecStatus = TargetSpecStatus.OK
    subject_type: SubjectType = SubjectType.PERSON
    subject_count: int | None = None
    framing: Framing = Framing.FULL_BODY
    confidence: float = 0.0
    object_label: str | None = None

    def __post_init__(self) -> None:
        if self.schema_version not in SUPPORTED_SCHEMA_VERSIONS:
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
        if self.object_label is not None:
            if not isinstance(self.object_label, str):
                raise TypeError("TargetSpec objectLabel must be a string or null")
            if self.schema_version == "0.1":
                raise ValueError("TargetSpec objectLabel requires schemaVersion 0.2")
            if self.subject_type is not SubjectType.OBJECT:
                raise ValueError("TargetSpec objectLabel is only valid for subjectType=object")
            if not OBJECTS365_YOLO26.is_object_label(self.object_label):
                raise ValueError(
                    "TargetSpec objectLabel must be an exact Objects365 canonical object label"
                )
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
            "objectLabel",
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

        # Payloads created before objectLabel existed often omitted the version.
        # Keep interpreting those as v0.1; new v0.2 producers must be explicit.
        schema_version = _string_field(payload, "schemaVersion", default="0.1")
        if schema_version == "0.1" and "objectLabel" in payload:
            raise ValueError("TargetSpec v0.1 must not contain objectLabel")
        session_id = _string_field(payload, "sessionId")
        raw_text = _string_field(payload, "rawText", allow_empty=True)
        object_label = payload.get("objectLabel")
        if object_label is not None and not isinstance(object_label, str):
            raise TypeError("TargetSpec objectLabel must be a string or null")
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
            object_label=object_label,
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
        payload: dict[str, Any] = {
            "schemaVersion": self.schema_version,
            "sessionId": self.session_id,
            "status": self.status.value,
            "subjectType": self.subject_type.value,
        }
        if self.schema_version == "0.2":
            payload["objectLabel"] = self.object_label
        payload.update(
            {
                "subjectCount": self.subject_count,
                "framing": self.framing.value,
                "rawText": self.raw_text,
                "confidence": float(self.confidence),
                "source": self.source.value,
            }
        )
        return payload


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
