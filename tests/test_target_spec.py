import json

import pytest

from ai.target_spec import (
    Framing,
    SubjectType,
    TargetSpec,
    TargetSpecSource,
    TargetSpecStatus,
)


def valid_payload(**overrides):
    payload = {
        "schemaVersion": "0.1",
        "sessionId": "sess_20260812_001",
        "status": "ok",
        "subjectType": "person",
        "subjectCount": 2,
        "framing": "closeup",
        "rawText": "친구 두 명이랑 같이 나오게, 얼굴 크게 찍어줘",
        "confidence": 0.9,
        "source": "clova",
    }
    payload.update(overrides)
    return payload


def test_target_spec_parses_documented_payload_and_round_trips():
    payload = valid_payload()

    target_spec = TargetSpec.from_json(json.dumps(payload, ensure_ascii=False))

    assert target_spec.status is TargetSpecStatus.OK
    assert target_spec.subject_type is SubjectType.PERSON
    assert target_spec.framing is Framing.CLOSEUP
    assert target_spec.source is TargetSpecSource.CLOVA
    assert target_spec.to_dict() == payload


def test_target_spec_applies_documented_defaults():
    target_spec = TargetSpec.from_dict(
        {
            "sessionId": "session-1",
            "rawText": "사람을 찍어줘",
            "source": "ondevice",
        }
    )

    assert target_spec.schema_version == "0.1"
    assert target_spec.status is TargetSpecStatus.OK
    assert target_spec.subject_type is SubjectType.PERSON
    assert target_spec.subject_count is None
    assert target_spec.framing is Framing.FULL_BODY
    assert target_spec.confidence == 0.0


@pytest.mark.parametrize("subject_count", [True, 0, -1, 1.5, "2"])
def test_target_spec_rejects_invalid_subject_count(subject_count):
    with pytest.raises(ValueError, match="subjectCount"):
        TargetSpec.from_dict(valid_payload(subjectCount=subject_count))


def test_failed_target_spec_may_keep_an_empty_raw_text():
    target_spec = TargetSpec.from_dict(valid_payload(status="failed", rawText=""))

    assert target_spec.status is TargetSpecStatus.FAILED


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("schemaVersion", "9.9"),
        ("status", "unknown"),
        ("subjectType", "animal"),
        ("framing", "portrait"),
        ("confidence", 1.1),
        ("source", "other"),
    ],
)
def test_target_spec_rejects_values_outside_the_documented_contract(field, value):
    with pytest.raises(ValueError):
        TargetSpec.from_dict(valid_payload(**{field: value}))


def test_target_spec_rejects_unknown_fields_instead_of_silently_ignoring_them():
    with pytest.raises(ValueError, match="Unknown TargetSpec fields"):
        TargetSpec.from_dict(valid_payload(targetLabel="bottle"))


def test_direct_target_spec_constructor_rejects_boolean_confidence():
    with pytest.raises(TypeError, match="confidence"):
        TargetSpec(
            session_id="session-1",
            raw_text="사람을 찍어줘",
            source=TargetSpecSource.ONDEVICE,
            confidence=True,
        )
