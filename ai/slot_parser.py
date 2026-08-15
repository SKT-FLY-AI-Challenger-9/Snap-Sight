import re

from ai.target_spec import Framing, SubjectType, TargetSpec, TargetSpecSource, TargetSpecStatus

# STT는 숫자를 한글이 아닌 아라비아 숫자로 인식하는 경우가 많다 (예: "2명").
DIGIT_COUNT_PATTERN = re.compile(r"(\d+)\s*명")

COUNT_WORDS = {
    "혼자": 1, "한 명": 1, "한명": 1,
    "두 명": 2, "두명": 2, "둘": 2,
    "세 명": 3, "세명": 3, "셋": 3,
}

FRAMING_KEYWORDS = {
    "얼굴": Framing.CLOSEUP, "클로즈업": Framing.CLOSEUP,
    "전신": Framing.FULL_BODY, "몸 전체": Framing.FULL_BODY,
    "풍경": Framing.WIDE, "배경": Framing.WIDE,
}

# subjectType이 person도 object도 아닌 경우만 다룬다 (object는 OBJECT_LABEL_KEYWORDS로 판정).
SUBJECT_TYPE_KEYWORDS = {
    "풍경": SubjectType.LANDSCAPE, "경치": SubjectType.LANDSCAPE,
}

# 값은 ai/taxonomy(OBJECTS365_YOLO26)의 canonical label과 정확히 일치해야 한다.
# tests/test_slot_parser.py가 모든 값을 taxonomy로 검증해 drift를 잡아준다.
# "새"처럼 Objects365에 단일 class로 없는 경우는 매핑하지 않는다 (schema 문서 지침).
OBJECT_LABEL_KEYWORDS = {
    "머그컵": "cup", "컵": "cup",
    "물병": "bottle", "병": "bottle",
    "와인잔": "wine glass",
    "그릇": "bowl",
    "의자": "chair",
    "소파": "couch",
    "화분": "potted plant", "꽃": "potted plant",
    "침대": "bed",
    "식탁": "dining table", "테이블": "dining table",
    "책": "book",
    "시계": "clock",
    "꽃병": "vase",
    "백팩": "backpack", "가방": "backpack",
    "핸드백": "handbag",
    "캐리어": "suitcase",
    "우산": "umbrella",
    "노트북": "laptop",
    "휴대폰": "cell phone", "핸드폰": "cell phone",
    "곰인형": "teddy bear", "인형": "teddy bear",
    "케이크": "cake",
    "자전거": "bicycle",
    "자동차": "car",
    "강아지": "dog",
    "고양이": "cat",
}


def parse_target_spec(
    text: str,
    session_id: str,
    source: TargetSpecSource = TargetSpecSource.ONDEVICE,
) -> TargetSpec:
    """규칙 기반으로 텍스트를 파싱해 TargetSpec을 반환한다. 매칭 안 되면 기본값 유지."""
    subject_type = SubjectType.PERSON
    object_label: str | None = None

    for keyword, label in OBJECT_LABEL_KEYWORDS.items():
        if keyword in text:
            subject_type = SubjectType.OBJECT
            object_label = label
            break
    else:
        for keyword, candidate_type in SUBJECT_TYPE_KEYWORDS.items():
            if keyword in text:
                subject_type = candidate_type
                break

    subject_count = None
    digit_match = DIGIT_COUNT_PATTERN.search(text)
    if digit_match and int(digit_match.group(1)) >= 1:
        subject_count = int(digit_match.group(1))
    else:
        for word, count in COUNT_WORDS.items():
            if word in text:
                subject_count = count
                break

    framing = Framing.FULL_BODY
    for keyword, candidate_framing in FRAMING_KEYWORDS.items():
        if keyword in text:
            framing = candidate_framing
            break

    subject_matched = subject_type is not SubjectType.PERSON or object_label is not None
    count_matched = subject_count is not None
    framing_matched = framing is not Framing.FULL_BODY
    matched = sum([subject_matched, count_matched, framing_matched])
    confidence = round(0.4 + 0.2 * matched, 2)

    return TargetSpec(
        schema_version="0.2",
        session_id=session_id,
        status=TargetSpecStatus.OK,
        subject_type=subject_type,
        object_label=object_label,
        subject_count=subject_count,
        framing=framing,
        raw_text=text,
        confidence=confidence,
        source=source,
    )


if __name__ == "__main__":
    for s in [
        "친구 두 명이랑 같이 나오게, 얼굴 크게 찍어줘",
        "그냥 사진 찍어줘",
        "풍경 위주로 찍어줘",
        "저 컵 예쁘게 찍어줘",
        "혼자 전신 나오게 찍어줘",
    ]:
        print(s, "->", parse_target_spec(s, session_id="sess_debug").to_dict())
