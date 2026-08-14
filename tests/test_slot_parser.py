# tests/test_slot_parser.py
"""ai/slot_parser.parse_target_spec의 규칙 기반 슬롯 추출 결과를 샘플 문장으로 검증하는 테스트."""

from ai.slot_parser import parse_target_spec


def test_person_count_and_closeup_framing_parsed():
    spec = parse_target_spec("친구 두 명이랑 같이 나오게, 얼굴 크게 찍어줘", session_id="sess_1")

    assert spec.subjectType == "person"
    assert spec.objectLabel is None
    assert spec.subjectCount == 2
    assert spec.framing == "closeup"
    assert spec.confidence == 0.8


def test_no_keywords_falls_back_to_defaults():
    spec = parse_target_spec("그냥 사진 찍어줘", session_id="sess_2")

    assert spec.subjectType == "person"
    assert spec.objectLabel is None
    assert spec.subjectCount is None
    assert spec.framing == "full_body"
    assert spec.confidence == 0.4


def test_landscape_subject_type_and_wide_framing_parsed():
    spec = parse_target_spec("풍경 위주로 찍어줘", session_id="sess_3")

    assert spec.subjectType == "landscape"
    assert spec.framing == "wide"
    assert spec.subjectCount is None
    assert spec.confidence == 0.8


def test_object_label_sets_subject_type_to_object():
    spec = parse_target_spec("저 컵 예쁘게 찍어줘", session_id="sess_4")

    assert spec.subjectType == "object"
    assert spec.objectLabel == "cup"
    assert spec.confidence == 0.6


def test_count_keyword_matching_default_framing_value():
    spec = parse_target_spec("혼자 전신 나오게 찍어줘", session_id="sess_5")

    assert spec.subjectType == "person"
    assert spec.subjectCount == 1
    assert spec.framing == "full_body"
    assert spec.confidence == 0.6


def test_digit_count_pattern_parsed():
    """STT가 숫자를 아라비아 숫자로 인식하는 경우("2명")도 subjectCount로 잡아야 한다."""
    spec = parse_target_spec("친구 2명이랑 같이 나오게 얼굴 크게 찍어 줘", session_id="sess_8")

    assert spec.subjectCount == 2
    assert spec.framing == "closeup"


def test_unrecognized_object_keeps_safe_defaults():
    """objectLabel 목록에 없는 사물은 person으로 잘못 분류하지 않고 null로 안전하게 폴백한다."""
    spec = parse_target_spec("저 냉장고 좀 찍어줘", session_id="sess_6")

    assert spec.subjectType == "person"
    assert spec.objectLabel is None
    assert spec.confidence == 0.4
    assert spec.rawText == "저 냉장고 좀 찍어줘"


def test_schema_metadata_fields_are_populated():
    spec = parse_target_spec("사진 찍어줘", session_id="sess_7", source="clova")

    assert spec.schemaVersion == "0.2"
    assert spec.sessionId == "sess_7"
    assert spec.status == "ok"
    assert spec.source == "clova"
