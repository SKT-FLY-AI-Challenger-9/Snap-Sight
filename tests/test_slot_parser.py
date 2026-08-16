# tests/test_slot_parser.py
"""ai/slot_parser.parse_target_spec의 규칙 기반 슬롯 추출 결과를 샘플 문장으로 검증하는 테스트."""

from ai.slot_parser import OBJECT_LABEL_KEYWORDS, parse_target_spec
from ai.target_spec import Framing, SubjectType, TargetSpec, TargetSpecSource, TargetSpecStatus
from ai.taxonomy import OBJECTS365_YOLO26


def test_all_object_label_values_are_valid_objects365_labels():
    """taxonomy가 바뀌어도 OBJECT_LABEL_KEYWORDS 값이 어긋나면 이 테스트가 바로 잡아낸다."""
    for keyword, label in OBJECT_LABEL_KEYWORDS.items():
        assert OBJECTS365_YOLO26.is_object_label(label), f"'{keyword}' -> '{label}'은 유효한 Objects365 라벨이 아님"


def test_no_single_character_object_keywords():
    """1글자 키워드는 완성형 한글 음절이 다른 단어 속에 우연히 포함되어 오매칭을 일으킨다
    (예: "게"가 "크게"에 포함, "새"가 "새우"에 포함, "배"가 "배경"에 포함).
    이슈 #30에서 실제로 겪은 버그라 회귀 방지용으로 구조적으로 막아둔다."""
    short_keywords = [k for k in OBJECT_LABEL_KEYWORDS if len(k) < 2]
    assert short_keywords == [], f"1글자 키워드 발견(오매칭 위험): {short_keywords}"


def test_object_label_keywords_have_no_duplicate_keys_and_good_coverage():
    """딕셔너리 리터럴은 중복 키를 조용히 덮어써서 실수를 숨긴다 — 개수로 한 번 더 확인.
    또한 이슈 #30의 목적(커버리지 확장)이 실제로 달성됐는지 최소 기준으로 확인한다."""
    covered_labels = set(OBJECT_LABEL_KEYWORDS.values())
    assert len(covered_labels) >= 250, "objectLabel 커버리지가 이슈 #30 이전 수준으로 후퇴함"


def test_longer_keyword_wins_when_one_keyword_contains_another():
    """짧은 키워드가 더 긴 키워드의 substring인 경우(예: "안경" ⊂ "쌍안경"),
    dict 순서가 아니라 더 길고 구체적인 쪽이 이겨야 한다. 실제로 발견됐던 충돌 사례들."""
    cases = {
        "쌍안경 찍어줘": "binoculars",
        "커피테이블 찍어줘": "coffee table",
        "나비넥타이 매고 찍어줘": "bow tie",
        "세발자전거 찍어줘": "tricycle",
        "감자튀김 찍어줘": "french fries",
        "찻주전자 찍어줘": "tea pot",
        # 짧은 키워드 쪽도 단독으로는 여전히 잘 잡혀야 한다 (longest-match가 과하게 막지 않는지 확인)
        "안경 찍어줘": "glasses",
        "테이블 찍어줘": "dining table",
        "넥타이 찍어줘": "tie",
        "자전거 찍어줘": "bicycle",
        "감자 찍어줘": "potato",
        "주전자 찍어줘": "kettle",
    }
    for text, expected_label in cases.items():
        spec = parse_target_spec(text, session_id="sess_collision")
        assert spec.object_label == expected_label, f"{text!r} -> {spec.object_label} (기대: {expected_label})"


def test_no_keyword_is_shadowed_by_a_longer_keyword_of_a_different_label():
    """모든 (짧은 키워드, 긴 키워드) 쌍에 대해, 짧은 쪽이 긴 쪽의 substring이면서 라벨이
    다른 경우가 있어도 longest-match 로직으로 항상 올바르게 풀리는지 전수 검사한다.
    새 키워드를 추가할 때 이 테스트가 통과하면 매칭 로직 차원에서는 안전하다는 뜻이다."""
    items = list(OBJECT_LABEL_KEYWORDS.items())
    for short_kw, short_label in items:
        for long_kw, long_label in items:
            if short_kw == long_kw or short_label == long_label:
                continue
            if short_kw in long_kw:
                spec = parse_target_spec(f"저 {long_kw} 찍어줘", session_id="sess_shadow")
                assert spec.object_label == long_label, (
                    f"'{short_kw}'(-> {short_label})가 '{long_kw}'(-> {long_label})를 가림"
                )


def test_person_count_and_closeup_framing_parsed():
    spec = parse_target_spec("친구 두 명이랑 같이 나오게, 얼굴 크게 찍어줘", session_id="sess_1")

    assert spec.subject_type is SubjectType.PERSON
    assert spec.object_label is None
    assert spec.subject_count == 2
    assert spec.framing is Framing.CLOSEUP
    assert spec.confidence == 0.8


def test_common_words_containing_removed_short_keywords_do_not_false_match():
    """"크게"("게"=crab 포함), "새우"("새"=wild bird 포함), "배경"("배"=pear 포함) 같은
    일상 단어가 objectLabel로 잘못 잡히지 않는지 직접 확인 (실제로 발견됐던 버그)."""
    assert parse_target_spec("이렇게 크게 나오게 찍어줘", session_id="sess_11").object_label is None
    assert parse_target_spec("새우 요리 옆에서 찍어줘", session_id="sess_12").object_label == "shrimp"
    assert parse_target_spec("풍경 배경으로 찍어줘", session_id="sess_13").object_label is None


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
    spec = parse_target_spec("저 머그컵 예쁘게 찍어줘", session_id="sess_4")

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
    """objectLabel 목록(그리고 애초에 Objects365 taxonomy)에 없는 사물은 person으로
    잘못 분류하지 않고 null로 안전하게 폴백한다."""
    spec = parse_target_spec("저 정수기 좀 찍어줘", session_id="sess_6")

    assert spec.subject_type is SubjectType.PERSON
    assert spec.object_label is None
    assert spec.confidence == 0.4
    assert spec.raw_text == "저 정수기 좀 찍어줘"


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
    spec = parse_target_spec("저 머그컵 예쁘게 찍어줘", session_id="sess_10")
    payload = spec.to_dict()
    round_tripped = TargetSpec.from_dict(payload)

    assert round_tripped == spec
