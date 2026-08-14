import re
from dataclasses import dataclass
from typing import Optional

SCHEMA_VERSION = "0.2"

# STT는 숫자를 한글이 아닌 아라비아 숫자로 인식하는 경우가 많다 (예: "2명").
DIGIT_COUNT_PATTERN = re.compile(r"(\d+)\s*명")


@dataclass
class TargetSpec:
    schemaVersion: str = SCHEMA_VERSION
    sessionId: str = ""
    status: str = "ok"
    subjectType: str = "person"
    objectLabel: Optional[str] = None
    subjectCount: Optional[int] = None
    framing: str = "full_body"
    rawText: str = ""
    confidence: float = 0.0
    source: str = "clova"


COUNT_WORDS = {
    "혼자": 1, "한 명": 1, "한명": 1,
    "두 명": 2, "두명": 2, "둘": 2,
    "세 명": 3, "세명": 3, "셋": 3,
}

FRAMING_KEYWORDS = {
    "얼굴": "closeup", "클로즈업": "closeup",
    "전신": "full_body", "몸 전체": "full_body",
    "풍경": "wide", "배경": "wide",
}

# subjectType이 person도 object도 아닌 경우만 다룬다 (object는 OBJECT_LABEL_KEYWORDS로 판정).
SUBJECT_TYPE_KEYWORDS = {
    "풍경": "landscape", "경치": "landscape",
}

# ai/target_spec_schema.md의 objectLabel 허용값(YOLO COCO 클래스 기준 초안)과 동일하게 유지한다.
OBJECT_LABEL_KEYWORDS = {
    "머그컵": "cup", "컵": "cup",
    "물병": "bottle", "병": "bottle",
    "와인잔": "wine_glass",
    "그릇": "bowl",
    "의자": "chair",
    "소파": "couch",
    "화분": "potted_plant", "꽃": "potted_plant",
    "침대": "bed",
    "식탁": "dining_table", "테이블": "dining_table",
    "책": "book",
    "시계": "clock",
    "꽃병": "vase",
    "백팩": "backpack", "가방": "backpack",
    "핸드백": "handbag",
    "캐리어": "suitcase",
    "우산": "umbrella",
    "노트북": "laptop",
    "휴대폰": "cell_phone", "핸드폰": "cell_phone",
    "곰인형": "teddy_bear", "인형": "teddy_bear",
    "케이크": "cake",
    "자전거": "bicycle",
    "자동차": "car",
    "강아지": "dog",
    "고양이": "cat",
    "새": "bird",
}


def parse_target_spec(text: str, session_id: str, source: str = "clova") -> TargetSpec:
    """규칙 기반으로 텍스트를 파싱해 TargetSpec을 반환한다. 매칭 안 되면 기본값 유지."""
    spec = TargetSpec(rawText=text, sessionId=session_id, source=source)

    for keyword, label in OBJECT_LABEL_KEYWORDS.items():
        if keyword in text:
            spec.subjectType = "object"
            spec.objectLabel = label
            break
    else:
        for keyword, subject_type in SUBJECT_TYPE_KEYWORDS.items():
            if keyword in text:
                spec.subjectType = subject_type
                break

    digit_match = DIGIT_COUNT_PATTERN.search(text)
    if digit_match:
        spec.subjectCount = int(digit_match.group(1))
    else:
        for word, count in COUNT_WORDS.items():
            if word in text:
                spec.subjectCount = count
                break

    for keyword, framing in FRAMING_KEYWORDS.items():
        if keyword in text:
            spec.framing = framing
            break

    subject_matched = spec.subjectType != "person" or spec.objectLabel is not None
    count_matched = spec.subjectCount is not None
    framing_matched = spec.framing != "full_body"
    matched = sum([subject_matched, count_matched, framing_matched])
    spec.confidence = round(0.4 + 0.2 * matched, 2)

    return spec


if __name__ == "__main__":
    from dataclasses import asdict

    for s in [
        "친구 두 명이랑 같이 나오게, 얼굴 크게 찍어줘",
        "그냥 사진 찍어줘",
        "풍경 위주로 찍어줘",
        "저 컵 예쁘게 찍어줘",
        "혼자 전신 나오게 찍어줘",
    ]:
        print(s, "->", asdict(parse_target_spec(s, session_id="sess_debug")))
