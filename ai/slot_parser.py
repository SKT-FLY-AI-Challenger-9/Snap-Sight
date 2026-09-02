"""규칙 기반 슬롯 파서 — 하이브리드 해석의 참조 구현.

frontend `cv/SlotParser.kt` 는 이 파일의 Kotlin 포팅으로, 앱이 서버 왕복 전에 같은
규칙을 기기에서 먼저 돌린다 (신호가 잡히면 서버 생략, 못 알아들은 발화만 LLM 폴백).
키워드 표·규칙·confidence 산식을 고치면 반드시 양쪽을 함께 고친다 —
`tests/test_slot_parser.py` 와 `SlotParserTest.kt` 가 각각 미러 검증한다.
"""

import re

from ai.target_spec import Framing, SubjectType, TargetSpec, TargetSpecSource, TargetSpecStatus

# STT는 숫자를 한글이 아닌 아라비아 숫자로 인식하는 경우가 많다 (예: "2명").
DIGIT_COUNT_PATTERN = re.compile(r"(\d+)\s*명")

# confidence는 0.4/0.6/0.8/1.0 네 값만 가능하다 (신호 0~4개 매칭, 1.0 상한). 임계값은 실측
# 근거 없는 추정치이지만, 이 이산값 구조상 0.4~0.6 사이 어떤 값을 넣어도 "신호 0개 매칭"만
# 걸러내는 것과 동일해 실질적으로는 "신호가 하나도 안 잡혔는가"를 구분하는 경계다.
CONFIDENCE_THRESHOLD = 0.6

# 구도 요청 (2026-08-31): "구도 좋게 찍어줘"처럼 피사체 단어가 없어도 촬영 의도가 명확한
# 발화 — 매칭 신호로 세어 needs_clarification 으로 떨어지지 않게 한다 (실기기에서 이 발화가
# 0.4 로 떨어져 조준이 아예 시작되지 않는 문제). 앱은 이 키워드로 구도 모드도 무장한다.
# frontend cv/SlotParser.kt 의 COMPOSITION_KEYWORDS 와 항목·순서가 같아야 한다.
COMPOSITION_KEYWORDS = ("구도", "멋지게", "멋있게", "예쁘게", "이쁘게", "감성", "분위기", "상반신")

# 인물 요청 (2026-08-31): "사람 찍고 싶다"가 신호 0개로 needs_clarification 에 떨어져 일반
# 촬영 모드가 되던 문제 — subject_type 기본값이 PERSON 이라 "사람"이라는 명시가 신호로 세지지
# 않았다. 인물 단어를 주체 신호로 센다 (subject_type 은 그대로 PERSON). 사물 키워드가 먼저
# 매칭되므로 "아이스크림"의 "아이" 같은 포함 관계는 사물이 이긴다. 2글자 미만 금지 규칙 공유.
# frontend cv/SlotParser.kt 의 PERSON_KEYWORDS 와 항목·순서가 같아야 한다.
PERSON_KEYWORDS = (
    "사람", "인물", "친구", "가족", "아기", "아이", "엄마", "아빠",
    "할머니", "할아버지", "언니", "오빠", "누나", "동생",
)

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
# 서류(DOCUMENT, 2026-08-30): 서류·종이·신분증류 단어가 있으면 서류 모드 — 앱이 bbox 대신
# 텍스트 영역으로 프레이밍한다. 짧은 키워드가 다른 단어에 우연히 포함되지 않게 2글자 이상만 쓴다.
# frontend cv/SlotParser.kt 의 SUBJECT_TYPE_KEYWORDS 와 항목·순서가 같아야 한다.
SUBJECT_TYPE_KEYWORDS = {
    "풍경": SubjectType.LANDSCAPE, "경치": SubjectType.LANDSCAPE,
    "서류": SubjectType.DOCUMENT, "문서": SubjectType.DOCUMENT,
    "종이": SubjectType.DOCUMENT, "신분증": SubjectType.DOCUMENT,
    "주민등록증": SubjectType.DOCUMENT, "주민증": SubjectType.DOCUMENT,
    "면허증": SubjectType.DOCUMENT, "여권": SubjectType.DOCUMENT,
    "명함": SubjectType.DOCUMENT, "영수증": SubjectType.DOCUMENT,
    "계약서": SubjectType.DOCUMENT, "청구서": SubjectType.DOCUMENT,
    "고지서": SubjectType.DOCUMENT, "증명서": SubjectType.DOCUMENT,
    "학생증": SubjectType.DOCUMENT, "안내문": SubjectType.DOCUMENT,
    "편지": SubjectType.DOCUMENT, "처방전": SubjectType.DOCUMENT,
    "통장": SubjectType.DOCUMENT,
}

# 값은 ai/taxonomy(OBJECTS365_YOLO26)의 canonical label과 정확히 일치해야 한다.
# tests/test_slot_parser.py가 (1) 모든 값이 taxonomy에 실존하는지, (2) 키워드끼리
# substring으로 서로를 가리지 않는지(예: "가방"이 있으면 "여행가방"을 추가해도 절대 안 잡힘)
# 자동으로 검증한다 — 이슈 #30에서 1글자 키워드가 다른 단어에 우연히 포함되는 버그를 겪은 뒤
# "키워드 간 charging" 문제 자체를 구조적으로 막기 위해 추가한 규칙이다.
#
# ai/taxonomy/objects365_yolo26_v1.json의 170개(person 제외 169개) 라벨을 순서대로 검토해
# 자연스러운 한글 단어가 있는 것만 담았다. 없거나(예: crane, radiator) 너무 모호한 것
# (예: folder, marker)은 의도적으로 비웠다 — 향후 ai/tools/list_uncovered_object_labels.py로
# 빠진 라벨을 확인하고 확장할 수 있다.
OBJECT_LABEL_KEYWORDS = {
    "스니커즈": "sneakers", "운동화": "sneakers",
    "의자": "chair",
    "모자": "hat",
    "스탠드": "lamp",
    "물병": "bottle",
    "캐비닛": "cabinet/shelf", "선반": "cabinet/shelf",
    "머그컵": "cup",
    "자동차": "car",
    "안경": "glasses",
    "액자": "picture/frame",
    "책상": "desk", "테이블": "desk", "식탁": "desk",
    "핸드백": "handbag",
    "가로등": "street lights",
    "구두": "leather shoes",
    "베개": "pillow",
    "장갑": "glove",
    "화분": "potted plant",
    "팔찌": "bracelet",
    "생화": "flower",
    "텔레비전": "tv", "티비": "tv",
    "꽃병": "vase",
    "벤치": "bench",
    "와인잔": "wine glass",
    "부츠": "boots",
    "그릇": "bowl",
    "접시": "plate",
    "우산": "umbrella",
    "스피커": "speaker",
    "쓰레기통": "trash bin/can",
    "백팩": "backpack", "가방": "backpack",
    "소파": "couch",
    "벨트": "belt",
    "수건": "towel/napkin",
    "슬리퍼": "slippers",
    "넥타이": "tie",
    "침대": "bed",
    "신호등": "traffic light",
    "연필": "pen/pencil",
    "샌들": "sandals",
    "목걸이": "necklace",
    "거울": "mirror",
    "자전거": "bicycle",
    "하이힐": "high heels",
    "반지": "ring",
    "손목시계": "watch",
    "사과": "apple",
    "카메라": "camera",
    "양초": "candle",
    "곰인형": "teddy bear",
    "인형": "doll",
    "케이크": "cake",
    "오토바이": "motorcycle",
    "노트북": "laptop",
    "나이프": "knife",
    "표지판": "traffic sign",
    "휴대폰": "cell phone", "핸드폰": "cell phone",
    "트럭": "truck",
    "벽시계": "clock",
    "포크": "fork",
    "버스": "bus",
    "냄비": "pot/pan", "프라이팬": "pot/pan",
    "기타": "guitar",
    "키보드": "keyboard",
    "선풍기": "fan",
    "강아지": "dog",
    "숟가락": "spoon",
    "에어컨": "air conditioner",
    "마우스": "mouse",
    "오렌지": "orange",
    "바나나": "banana",
    "냉장고": "refrigerator",
    "토마토": "tomato",
    "도넛": "donut",
    "피자": "pizza",
    "가스레인지": "gas stove",
    "유모차": "stroller",
    "전자레인지": "microwave",
    "고양이": "cat",
    "호박": "pumpkin",
    "피아노": "piano",
    "티슈": "tissue",
    "당근": "carrot",
    "세탁기": "washing machine",
    "쿠키": "cookies",
    "도마": "cutting/chopping board",
    "가위": "scissors",
    "딸기": "strawberry",
    "캐리어": "suitcase",
    "포도": "grapes",
    "감자": "potato",
    "소화전": "fire hydrant",
    "소화기": "fire extinguisher",
    "젓가락": "chopsticks",
    "주전자": "kettle",
    "햄버거": "hamburger",
    "골프채": "golf club",
    "오이": "cucumber",
    "칫솔": "toothbrush",
    "계란": "egg",
    "바이올린": "violin",
    "양파": "onion",
    "아이스크림": "ice cream",
    "휠체어": "wheelchair",
    "자두": "plum",
    "수박": "watermelon",
    "양배추": "cabbage",
    "복숭아": "peach",
    "마늘": "garlic",
    "감자튀김": "french fries",
    "샌드위치": "sandwich",
    "태블릿": "tablet",
    "옥수수": "corn",
    "열쇠": "key",
    "쌀밥": "rice",
    "덤벨": "dumbbell",
    "상추": "lettuce",
    "치약": "toothpaste",
    "밥솥": "rice cooker",
    "드라이기": "hair drier",
    "초밥": "sushi",
    "스테이크": "steak",
    "국수": "noodles",
    "서양배": "pear",
    "래디시": "radish",
    "들새": "wild bird",
    "김치": "kimchi",
    "김밥": "gimbap",
    "만두": "mandu",
    "떡볶이": "tteokbokki",
    "가래떡": "ttoke",
    "반찬": "side dish", "밑반찬": "side dish",
    "수저": "cutlery", "식기": "cutlery",
    "국자": "ladle",
    "밥주걱": "rice spatula", "주걱": "rice spatula",
    "실리콘주걱": "silicon spatula", "뒤집개": "silicon spatula",
    "감자칼": "vegetable peeler", "필러": "vegetable peeler",
    "쟁반": "tray", "트레이": "tray",
    "에스프레소머신": "espresso machine", "커피머신": "espresso machine",
    "정수기": "purifier", "공기청정기": "purifier",
    "변기": "toilet bowl", "양변기": "toilet bowl",
    "세면대": "washstand", "세면기": "washstand",
    "출입문": "door", "현관문": "door", "문짝": "door",
    "창문": "window",
    "지붕": "roof",
    "간판": "sign",
    "책자": "book", "도서": "book",
    "머리빗": "hair brush", "헤어브러시": "hair brush",
    "머플러": "muffler", "목도리": "muffler",
    "스케이트화": "skating shoes", "스케이트": "skating shoes",
    "축구공": "ball", "농구공": "ball", "야구공": "ball",
    "농구골대": "basketball hoop", "농구대": "basketball hoop", "농구링": "basketball hoop",
    "골대": "goalpost", "골포스트": "goalpost",
    "당구채": "billiards cue", "큐대": "billiards cue",
    "탁구채": "table tennis racket", "탁구라켓": "table tennis racket",
    "필라테스기구": "pilates equipment", "필라테스": "pilates equipment",
    "킥보드": "scooter", "스쿠터": "scooter",
    "드론": "drone",
    "카라비너": "carabiner",
    "리코더": "recorder",
    "오카리나": "ocarina",
    "탬버린": "tambourine",
    "체온계": "thermometer", "온도계": "thermometer",
    "깻잎": "perilla leaf",
    "대파": "spring onion", "쪽파": "spring onion",
    "고추": "chili",
    "피망": "pimento",
    "애호박": "squash",
    "고구마": "sweet potato",
}


def parse_target_spec(
    text: str,
    session_id: str,
    source: TargetSpecSource = TargetSpecSource.ONDEVICE,
) -> TargetSpec:
    """규칙 기반으로 텍스트를 파싱해 TargetSpec을 반환한다. 매칭 안 되면 기본값 유지."""
    subject_type = SubjectType.PERSON
    object_label: str | None = None

    # 짧은 키워드가 더 긴 키워드의 substring인 경우가 있다 (예: "안경" ⊂ "쌍안경",
    # "넥타이" ⊂ "나비넥타이"). dict 순서로 먼저 걸리는 걸 쓰면 "쌍안경"이 "안경"으로
    # 잘못 잡히므로, 매칭된 키워드 중 가장 긴(= 가장 구체적인) 것을 우선한다.
    matched_keywords = [kw for kw in OBJECT_LABEL_KEYWORDS if kw in text]
    if matched_keywords:
        best_keyword = max(matched_keywords, key=len)
        subject_type = SubjectType.OBJECT
        object_label = OBJECT_LABEL_KEYWORDS[best_keyword]
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

    subject_matched = (
        subject_type is not SubjectType.PERSON
        or object_label is not None
        or any(keyword in text for keyword in PERSON_KEYWORDS)
    )
    count_matched = subject_count is not None
    framing_matched = framing is not Framing.FULL_BODY
    composition_matched = any(keyword in text for keyword in COMPOSITION_KEYWORDS)
    matched = sum([subject_matched, count_matched, framing_matched, composition_matched])
    confidence = round(min(1.0, 0.4 + 0.2 * matched), 2)
    status = (
        TargetSpecStatus.OK
        if confidence >= CONFIDENCE_THRESHOLD
        else TargetSpecStatus.NEEDS_CLARIFICATION
    )

    return TargetSpec(
        schema_version="0.2",
        session_id=session_id,
        status=status,
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
        "저 머그컵 예쁘게 찍어줘",
        "혼자 전신 나오게 찍어줘",
        "저 사과 찍어줘",
        "냉장고 찍어줘",
        "벽시계 찍어줘",
        "생화 예쁘게 찍어줘",
    ]:
        print(s, "->", parse_target_spec(s, session_id="sess_debug").to_dict())
