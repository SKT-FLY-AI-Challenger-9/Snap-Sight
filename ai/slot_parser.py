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

# confidence는 0.4/0.6/0.8/1.0 네 값만 가능하다 (신호 0~3개 매칭). 임계값은 실측 근거 없는
# 추정치이지만, 이 이산값 구조상 0.4~0.6 사이 어떤 값을 넣어도 "신호 0개 매칭"만 걸러내는
# 것과 동일해 실질적으로는 "신호가 하나도 안 잡혔는가"를 구분하는 경계다.
CONFIDENCE_THRESHOLD = 0.6

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
}

# 값은 ai/taxonomy(OBJECTS365_YOLO26)의 canonical label과 정확히 일치해야 한다.
# tests/test_slot_parser.py가 (1) 모든 값이 taxonomy에 실존하는지, (2) 키워드끼리
# substring으로 서로를 가리지 않는지(예: "가방"이 있으면 "여행가방"을 추가해도 절대 안 잡힘)
# 자동으로 검증한다 — 이슈 #30에서 1글자 키워드가 다른 단어에 우연히 포함되는 버그를 겪은 뒤
# "키워드 간 charging" 문제 자체를 구조적으로 막기 위해 추가한 규칙이다.
#
# ai/taxonomy/objects365_yolo26_v1.json의 365개(person 제외 364개) 라벨을 순서대로 검토해
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
    "책상": "desk",
    "핸드백": "handbag",
    "가로등": "street lights",
    "헬멧": "helmet",
    "구두": "leather shoes",
    "베개": "pillow",
    "장갑": "glove",
    "화분": "potted plant",
    "팔찌": "bracelet",
    "생화": "flower",
    "텔레비전": "tv", "티비": "tv",
    "수납함": "storage box",
    "꽃병": "vase",
    "벤치": "bench",
    "와인잔": "wine glass",
    "부츠": "boots",
    "그릇": "bowl",
    "접시": "plate",
    "식탁": "dining table", "테이블": "dining table",
    "우산": "umbrella",
    "보트": "boat",
    "깃발": "flag",
    "스피커": "speaker",
    "쓰레기통": "trash bin/can",
    "백팩": "backpack", "가방": "backpack",
    "소파": "couch",
    "벨트": "belt",
    "카펫": "carpet", "러그": "carpet",
    "바구니": "basket",
    "수건": "towel/napkin",
    "슬리퍼": "slippers",
    "양동이": "barrel/bucket",
    "커피테이블": "coffee table",
    "장난감": "toy",
    "넥타이": "tie",
    "침대": "bed",
    "신호등": "traffic light",
    "연필": "pen/pencil",
    "마이크": "microphone",
    "샌들": "sandals",
    "통조림": "canned",
    "목걸이": "necklace",
    "거울": "mirror",
    "수도꼭지": "faucet",
    "자전거": "bicycle",
    "하이힐": "high heels",
    "반지": "ring",
    "손목시계": "watch",
    "싱크대": "sink",
    "물고기": "fish",
    "사과": "apple",
    "카메라": "camera",
    "양초": "candle",
    "곰인형": "teddy bear", "인형": "teddy bear",
    "케이크": "cake",
    "오토바이": "motorcycle",
    "노트북": "laptop",
    "나이프": "knife",
    "표지판": "traffic sign",
    "휴대폰": "cell phone", "핸드폰": "cell phone",
    "트럭": "truck",
    "콘센트": "power outlet",
    "벽시계": "clock",
    "드럼": "drum",
    "포크": "fork",
    "버스": "bus",
    "옷걸이": "hanger",
    "협탁": "nightstand",
    "냄비": "pot/pan", "프라이팬": "pot/pan",
    "기타": "guitar",
    "찻주전자": "tea pot",
    "키보드": "keyboard",
    "삼각대": "tripod",
    "선풍기": "fan",
    "강아지": "dog",
    "숟가락": "spoon",
    "화이트보드": "blackboard/whiteboard", "칠판": "blackboard/whiteboard",
    "풍선": "balloon",
    "에어컨": "air conditioner",
    "마우스": "mouse",
    "전화기": "telephone",
    "오렌지": "orange",
    "바나나": "banana",
    "비행기": "airplane",
    "스키": "skis",
    "축구공": "soccer",
    "카트": "trolley",
    "오븐": "oven",
    "리모컨": "remote",
    "냉장고": "refrigerator",
    "기차": "train",
    "토마토": "tomato",
    "텐트": "tent",
    "샴푸": "shampoo/shower gel",
    "헤드폰": "head phone",
    "랜턴": "lantern",
    "도넛": "donut",
    "요트": "sailboat",
    "피자": "pizza",
    "본체": "computer box",
    "코끼리": "elephant",
    "가스레인지": "gas stove",
    "브로콜리": "broccoli",
    "변기": "toilet",
    "유모차": "stroller",
    "야구방망이": "baseball bat",
    "전자레인지": "microwave",
    "스케이트보드": "skateboard",
    "서핑보드": "surfboard",
    "고양이": "cat",
    "레몬": "lemon",
    "얼룩말": "zebra",
    "오리": "duck",
    "기린": "giraffe",
    "호박": "pumpkin",
    "피아노": "piano",
    "티슈": "tissue",
    "당근": "carrot",
    "세탁기": "washing machine",
    "쿠키": "cookies",
    "도마": "cutting/chopping board",
    "테니스라켓": "tennis racket",
    "사탕": "candy",
    "가위": "scissors",
    "야구공": "baseball",
    "딸기": "strawberry",
    "나비넥타이": "bow tie",
    "비둘기": "pigeon",
    "고추": "pepper",
    "커피머신": "coffee machine",
    "욕조": "bathtub",
    "스노보드": "snowboard",
    "캐리어": "suitcase",
    "포도": "grapes",
    "사다리": "ladder",
    "농구공": "basketball",
    "감자": "potato",
    "그림붓": "paint brush",
    "프린터": "printer",
    "소화전": "fire hydrant",
    "소화기": "fire extinguisher",
    "거위": "goose",
    "프로젝터": "projector",
    "소시지": "sausage",
    "멀티탭": "extension cord",
    "테니스공": "tennis ball",
    "젓가락": "chopsticks",
    "파이": "pie",
    "프리스비": "frisbee",
    "주전자": "kettle",
    "햄버거": "hamburger",
    "골프채": "golf club",
    "오이": "cucumber",
    "믹서기": "blender",
    "핫도그": "hot dog",
    "칫솔": "toothbrush",
    "망고": "mango",
    "사슴": "deer",
    "계란": "egg",
    "바이올린": "violin",
    "양파": "onion",
    "아이스크림": "ice cream",
    "테이프": "tape",
    "휠체어": "wheelchair",
    "자두": "plum",
    "비누": "bar soap",
    "저울": "scale",
    "수박": "watermelon",
    "양배추": "cabbage",
    "공유기": "router/modem",
    "골프공": "golf ball",
    "파인애플": "pine apple",
    "소방차": "fire truck",
    "복숭아": "peach",
    "첼로": "cello",
    "메모지": "notepaper",
    "세발자전거": "tricycle",
    "토스터": "toaster",
    "헬리콥터": "helicopter",
    "강낭콩": "green beans",
    "시가": "cigar",
    "이어폰": "earphone",
    "펭귄": "penguin",
    "그네": "swing",
    "라디오": "radio",
    "백조": "swan",
    "마늘": "garlic",
    "감자튀김": "french fries",
    "아보카도": "avocado",
    "색소폰": "saxophone",
    "트럼펫": "trumpet",
    "샌드위치": "sandwich",
    "키위": "kiwi fruit",
    "낚싯대": "fishing rod",
    "체리": "cherry",
    "태블릿": "tablet",
    "옥수수": "corn",
    "열쇠": "key",
    "드라이버": "screwdriver",
    "지구본": "globe",
    "빗자루": "broom",
    "배구공": "volleyball",
    "망치": "hammer",
    "가지": "eggplant",
    "트로피": "trophy",
    "쌀밥": "rice",
    "줄자": "tape measure/ruler",
    "덤벨": "dumbbell",
    "스테이플러": "stapler",
    "낙타": "camel",
    "상추": "lettuce",
    "금붕어": "goldfish",
    "메달": "medal",
    "치약": "toothpaste",
    "새우": "shrimp",
    "트롬본": "trombone",
    "석류": "pomegranate",
    "코코넛": "coconut",
    "해파리": "jellyfish",
    "버섯": "mushroom",
    "계산기": "calculator",
    "러닝머신": "treadmill",
    "나비": "butterfly",
    "에그타르트": "egg tart",
    "치즈": "cheese",
    "돼지": "pig",
    "밥솥": "rice cooker",
    "튜바": "tuba",
    "파파야": "papaya",
    "드라이기": "hair drier",
    "대파": "green onion",
    "돌고래": "dolphin",
    "초밥": "sushi",
    "당나귀": "donkey",
    "전동드릴": "electric drill",
    "거북이": "tortoise/turtle",
    "앵무새": "parrot",
    "플루트": "flute",
    "계량컵": "measuring cup",
    "상어": "shark",
    "스테이크": "steak",
    "쌍안경": "binoculars",
    "라마": "llama",
    "국수": "noodles",
    "대걸레": "mop",
    "현미경": "microscope",
    "바벨": "barbell",
    "사자": "lion",
    "북극곰": "polar bear",
    "라이터": "lighter",
    "물개": "seal",
    "머리빗": "comb",
    "지우개": "eraser",
    "필통": "pencil case",
    "탁구채": "table tennis paddle",
    "불가사리": "starfish",
    "독수리": "eagle",
    "원숭이": "monkey",
    "두리안": "durian",
    "토끼": "rabbit",
    "호른": "french horn",
    "구급차": "ambulance",
    "아스파라거스": "asparagus",
    "호버보드": "hoverboard",
    "파스타": "pasta",
    "열기구": "hotair balloon",
    "전기톱": "chainsaw",
    "랍스터": "lobster",
    "다리미": "iron",
    "손전등": "flashlight",

    # 1글자라 뺐던 라벨들 — 충돌 안 나는 2글자+ 대안으로 되살린 것들.
    # 완전한 동의어는 아니고(예: 젖소=cow 중 유제품용 소만 지칭) 근사치다.
    "야생곰": "bear",
    "닭고기": "chicken",
    "젖소": "cow",
    "꽃게": "crab",
    "가오리연": "kite",
    "서양배": "pear",
    "래디시": "radish",
    "선박": "ship",
    "감귤": "tangerine",
    "들새": "wild bird",
    # horse/saw/sheep/shovel: 자연스러운 2글자+ 대안을 못 찾아서 그대로 미커버.
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

    subject_matched = subject_type is not SubjectType.PERSON or object_label is not None
    count_matched = subject_count is not None
    framing_matched = framing is not Framing.FULL_BODY
    matched = sum([subject_matched, count_matched, framing_matched])
    confidence = round(0.4 + 0.2 * matched, 2)
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
