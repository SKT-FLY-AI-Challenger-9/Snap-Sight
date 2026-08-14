# tests/test_slot_parser.py
"""ai/slot_parser.parse_target_spec의 규칙 기반 슬롯 추출 결과를 샘플 문장으로 검증하는 테스트."""

from ai.slot_parser import OBJECT_LABEL_KEYWORDS, parse_target_spec
from ai.target_spec import Framing, SubjectType, TargetSpecSource, TargetSpecStatus
from ai.taxonomy import OBJECTS365_YOLO26


def test_all_object_label_values_are_valid_objects365_labels():
    """taxonomy가 바뀌어도 OBJECT_LABEL_KEYWORDS 값이 어긋나면 이 테스트가 바로 잡아낸다."""
    for keyword, label in OBJECT_LABEL_KEYWORDS.items():
        assert OBJECTS365_YOLO26.is_object_label(label), f"'{keyword}' -> '{label}'은 유효한 Objects365 라벨이 아님"


def test_person_count_and_closeup_framing_parsed():
    spec = parse_target_spec("친구 두 명이랑 같이 나오게, 얼굴 크게 찍어줘", session_id="sess_1")

    assert spec.subject_type is SubjectType.PERSON
    assert spec.object_label is None
    assert spec.subject_count == 2
    assert spec.framing is Framing.CLOSEUP
    assert spec.confidence == 0.8


def test_no_keywords_falls_back_to_defaults():
    spec = parse_target_spec("그냥 사진 찍어줘", session_id="sess_2")

    assert spec.subject_type is SubjectType.PERSON
    assert spec.object_label is None
    assert spec.subject_count is None
    assert spec.framing is Framing.FULL_BODY
    assert spec.confidence == 0.4


def test_landscape_subject_type_and_wide_framing_parsed():
    spec = parse_target_spec("풍경 위주로 찍어줘", session_id="sess_3")

    assert spec.subject_type is SubjectType.LANDSCAPE
    assert spec.framing is Framing.WIDE
    assert spec.subject_count is None
    assert spec.confidence == 0.8


def test_object_label_sets_subject_type_to_object():
    spec = parse_target_spec("저 컵 예쁘게 찍어줘", session_id="sess_4")

    assert spec.subject_type is SubjectType.OBJECT
    assert spec.object_label == "cup"
    assert spec.confidence == 0.6


def test_count_keyword_matching_default_framing_value():
    spec = parse_target_spec("혼자 전신 나오게 찍어줘", session_id="sess_5")

    assert spec.subject_type is SubjectType.PERSON
    assert spec.subject_count == 1
    assert spec.framing is Framing.FULL_BODY
    assert spec.confidence == 0.6


def test_digit_count_pattern_parsed():
    """STT가 숫자를 아라비아 숫자로 인식하는 경우("2명")도 subject_count로 잡아야 한다."""
    spec = parse_target_spec("친구 2명이랑 같이 나오게 얼굴 크게 찍어 줘", session_id="sess_8")

    assert spec.subject_count == 2
    assert spec.framing is Framing.CLOSEUP


def test_unrecognized_object_keeps_safe_defaults():
    """objectLabel 목록에 없는 사물은 person으로 잘못 분류하지 않고 null로 안전하게 폴백한다."""
    spec = parse_target_spec("저 냉장고 좀 찍어줘", session_id="sess_6")

    assert spec.subject_type is SubjectType.PERSON
    assert spec.object_label is None
    assert spec.confidence == 0.4
    assert spec.raw_text == "저 냉장고 좀 찍어줘"


def test_schema_metadata_fields_are_populated():
    spec = parse_target_spec("사진 찍어줘", session_id="sess_7")

    assert spec.schema_version == "0.2"
    assert spec.session_id == "sess_7"
    assert spec.status is TargetSpecStatus.OK
    assert spec.source is TargetSpecSource.ONDEVICE


def test_source_can_be_overridden():
    spec = parse_target_spec("사진 찍어줘", session_id="sess_9", source=TargetSpecSource.CLOVA)

    assert spec.source is TargetSpecSource.CLOVA


def test_returned_spec_serializes_to_valid_wire_json():
    """parse_target_spec의 결과가 실제로 ai/target_spec.py의 검증(생성자)을 통과한다는 걸 to_dict 왕복으로 재확인."""
    from ai.target_spec import TargetSpec

    spec = parse_target_spec("저 컵 예쁘게 찍어줘", session_id="sess_10")
    payload = spec.to_dict()
    round_tripped = TargetSpec.from_dict(payload)

    assert round_tripped == spec
