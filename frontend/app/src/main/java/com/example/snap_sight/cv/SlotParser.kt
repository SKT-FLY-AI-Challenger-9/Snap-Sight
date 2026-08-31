package com.example.snap_sight.cv

/**
 * `ai/slot_parser.py` 의 Kotlin 포팅 — 발화 텍스트를 규칙(키워드 사전 + 정규식)만으로
 * [TargetSpec] 으로 변환한다. 하이브리드 해석의 1단계: 여기서 신호가 잡히면(status=ok)
 * 서버 왕복 없이 즉시 조준을 시작하고, 못 알아들은 발화(needs_clarification)만
 * 백엔드의 LLM 폴백(`ai/llm_fallback.py`)으로 넘긴다.
 *
 * 키워드·규칙·confidence 산식은 Python 참조 구현과 의미가 같아야 한다. 한쪽을 고치면
 * 반드시 다른 쪽도 고친다 — [SlotParserTest]/`tests/test_slot_parser.py` 가 미러 검증한다.
 *
 * 이 파일은 android.* 에 의존하지 않는다 → JVM 단위 테스트에서 그대로 검증 가능.
 */
object SlotParser {

    // STT는 숫자를 한글이 아닌 아라비아 숫자로 인식하는 경우가 많다 (예: "2명").
    private val DIGIT_COUNT_PATTERN = Regex("""(\d+)\s*명""")

    // confidence는 0.4/0.6/0.8/1.0 네 값만 가능하다 (신호 0~4개 매칭, 1.0 상한). 임계값은 실측
    // 근거 없는 추정치이지만, 이 이산값 구조상 0.4~0.6 사이 어떤 값을 넣어도 "신호 0개 매칭"만
    // 걸러내는 것과 동일해 실질적으로는 "신호가 하나도 안 잡혔는가"를 구분하는 경계다.
    private const val CONFIDENCE_THRESHOLD = 0.6f

    // 구도 요청 (2026-08-31): "구도 좋게 찍어줘"처럼 피사체 단어가 없어도 촬영 의도가 명확한
    // 발화 — 매칭 신호로 세어 needs_clarification 으로 떨어지지 않게 한다 (실기기에서 이 발화가
    // 0.4 로 떨어져 조준이 아예 시작되지 않았다). MainActivity 는 이 키워드로 구도 모드도 무장한다.
    // `ai/slot_parser.py` 의 COMPOSITION_KEYWORDS 와 항목·순서가 같아야 한다.
    internal val COMPOSITION_KEYWORDS =
        listOf("구도", "멋지게", "멋있게", "예쁘게", "이쁘게", "감성", "분위기", "상반신")

    // 인물 요청 (2026-08-31): "사람 찍고 싶다"가 신호 0개로 needs_clarification 에 떨어져 일반
    // 촬영 모드가 되던 문제 — subjectType 기본값이 PERSON 이라 "사람"이라는 명시가 신호로 세지지
    // 않았다. 인물 단어를 주체 신호로 센다 (subjectType 은 그대로 PERSON). 사물 키워드가 먼저
    // 매칭되므로 "아이스크림"의 "아이" 같은 포함 관계는 사물이 이긴다. 2글자 미만 금지 규칙 공유.
    // `ai/slot_parser.py` 의 PERSON_KEYWORDS 와 항목·순서가 같아야 한다.
    internal val PERSON_KEYWORDS = listOf(
        "사람", "인물", "친구", "가족", "아기", "아이", "엄마", "아빠",
        "할머니", "할아버지", "언니", "오빠", "누나", "동생",
    )

    private val COUNT_WORDS = linkedMapOf(
        "혼자" to 1, "한 명" to 1, "한명" to 1,
        "두 명" to 2, "두명" to 2, "둘" to 2,
        "세 명" to 3, "세명" to 3, "셋" to 3,
    )

    private val FRAMING_KEYWORDS = linkedMapOf(
        "얼굴" to TargetSpec.Framing.CLOSEUP, "클로즈업" to TargetSpec.Framing.CLOSEUP,
        "전신" to TargetSpec.Framing.FULL_BODY, "몸 전체" to TargetSpec.Framing.FULL_BODY,
        "풍경" to TargetSpec.Framing.WIDE, "배경" to TargetSpec.Framing.WIDE,
    )

    // subjectType이 person도 object도 아닌 경우만 다룬다 (object는 OBJECT_LABEL_KEYWORDS로 판정).
    // 서류(DOCUMENT, 2026-08-30): 서류·종이·신분증류 단어가 있으면 서류 모드 — bbox 대신 텍스트
    // 영역으로 프레이밍한다. 짧은 키워드가 다른 단어에 우연히 포함되지 않게 2글자 이상만 쓴다.
    private val SUBJECT_TYPE_KEYWORDS = linkedMapOf(
        "풍경" to TargetSpec.SubjectType.LANDSCAPE, "경치" to TargetSpec.SubjectType.LANDSCAPE,
        "서류" to TargetSpec.SubjectType.DOCUMENT, "문서" to TargetSpec.SubjectType.DOCUMENT,
        "종이" to TargetSpec.SubjectType.DOCUMENT, "신분증" to TargetSpec.SubjectType.DOCUMENT,
        "주민등록증" to TargetSpec.SubjectType.DOCUMENT, "주민증" to TargetSpec.SubjectType.DOCUMENT,
        "면허증" to TargetSpec.SubjectType.DOCUMENT, "여권" to TargetSpec.SubjectType.DOCUMENT,
        "명함" to TargetSpec.SubjectType.DOCUMENT, "영수증" to TargetSpec.SubjectType.DOCUMENT,
        "계약서" to TargetSpec.SubjectType.DOCUMENT, "청구서" to TargetSpec.SubjectType.DOCUMENT,
        "고지서" to TargetSpec.SubjectType.DOCUMENT, "증명서" to TargetSpec.SubjectType.DOCUMENT,
        "학생증" to TargetSpec.SubjectType.DOCUMENT, "안내문" to TargetSpec.SubjectType.DOCUMENT,
        "편지" to TargetSpec.SubjectType.DOCUMENT, "처방전" to TargetSpec.SubjectType.DOCUMENT,
    )

    // 값은 taxonomy(objects365_yolo26_v1_labels.txt)의 canonical label과 정확히 일치해야 한다.
    // SlotParserTest 가 (1) 모든 값이 taxonomy에 실존하는지, (2) 키워드끼리 substring으로
    // 서로를 가리지 않는지 자동으로 검증한다 — `ai/slot_parser.py` 의 표와 항목·순서가 같아야 한다.
    internal val OBJECT_LABEL_KEYWORDS: Map<String, String> = linkedMapOf(
        "스니커즈" to "sneakers", "운동화" to "sneakers",
        "의자" to "chair",
        "모자" to "hat",
        "스탠드" to "lamp",
        "물병" to "bottle",
        "캐비닛" to "cabinet/shelf", "선반" to "cabinet/shelf",
        "머그컵" to "cup",
        "자동차" to "car",
        "안경" to "glasses",
        "액자" to "picture/frame",
        "책상" to "desk",
        "핸드백" to "handbag",
        "가로등" to "street lights",
        "헬멧" to "helmet",
        "구두" to "leather shoes",
        "베개" to "pillow",
        "장갑" to "glove",
        "화분" to "potted plant",
        "팔찌" to "bracelet",
        "생화" to "flower",
        "텔레비전" to "tv", "티비" to "tv",
        "수납함" to "storage box",
        "꽃병" to "vase",
        "벤치" to "bench",
        "와인잔" to "wine glass",
        "부츠" to "boots",
        "그릇" to "bowl",
        "접시" to "plate",
        "식탁" to "dining table", "테이블" to "dining table",
        "우산" to "umbrella",
        "보트" to "boat",
        "깃발" to "flag",
        "스피커" to "speaker",
        "쓰레기통" to "trash bin/can",
        "백팩" to "backpack", "가방" to "backpack",
        "소파" to "couch",
        "벨트" to "belt",
        "카펫" to "carpet", "러그" to "carpet",
        "바구니" to "basket",
        "수건" to "towel/napkin",
        "슬리퍼" to "slippers",
        "양동이" to "barrel/bucket",
        "커피테이블" to "coffee table",
        "장난감" to "toy",
        "넥타이" to "tie",
        "침대" to "bed",
        "신호등" to "traffic light",
        "연필" to "pen/pencil",
        "마이크" to "microphone",
        "샌들" to "sandals",
        "통조림" to "canned",
        "목걸이" to "necklace",
        "거울" to "mirror",
        "수도꼭지" to "faucet",
        "자전거" to "bicycle",
        "하이힐" to "high heels",
        "반지" to "ring",
        "손목시계" to "watch",
        "싱크대" to "sink",
        "물고기" to "fish",
        "사과" to "apple",
        "카메라" to "camera",
        "양초" to "candle",
        "곰인형" to "teddy bear", "인형" to "teddy bear",
        "케이크" to "cake",
        "오토바이" to "motorcycle",
        "노트북" to "laptop",
        "나이프" to "knife",
        "표지판" to "traffic sign",
        "휴대폰" to "cell phone", "핸드폰" to "cell phone",
        "트럭" to "truck",
        "콘센트" to "power outlet",
        "벽시계" to "clock",
        "드럼" to "drum",
        "포크" to "fork",
        "버스" to "bus",
        "옷걸이" to "hanger",
        "협탁" to "nightstand",
        "냄비" to "pot/pan", "프라이팬" to "pot/pan",
        "기타" to "guitar",
        "찻주전자" to "tea pot",
        "키보드" to "keyboard",
        "삼각대" to "tripod",
        "선풍기" to "fan",
        "강아지" to "dog",
        "숟가락" to "spoon",
        "화이트보드" to "blackboard/whiteboard", "칠판" to "blackboard/whiteboard",
        "풍선" to "balloon",
        "에어컨" to "air conditioner",
        "마우스" to "mouse",
        "전화기" to "telephone",
        "오렌지" to "orange",
        "바나나" to "banana",
        "비행기" to "airplane",
        "스키" to "skis",
        "축구공" to "soccer",
        "카트" to "trolley",
        "오븐" to "oven",
        "리모컨" to "remote",
        "냉장고" to "refrigerator",
        "기차" to "train",
        "토마토" to "tomato",
        "텐트" to "tent",
        "샴푸" to "shampoo/shower gel",
        "헤드폰" to "head phone",
        "랜턴" to "lantern",
        "도넛" to "donut",
        "요트" to "sailboat",
        "피자" to "pizza",
        "본체" to "computer box",
        "코끼리" to "elephant",
        "가스레인지" to "gas stove",
        "브로콜리" to "broccoli",
        "변기" to "toilet",
        "유모차" to "stroller",
        "야구방망이" to "baseball bat",
        "전자레인지" to "microwave",
        "스케이트보드" to "skateboard",
        "서핑보드" to "surfboard",
        "고양이" to "cat",
        "레몬" to "lemon",
        "얼룩말" to "zebra",
        "오리" to "duck",
        "기린" to "giraffe",
        "호박" to "pumpkin",
        "피아노" to "piano",
        "티슈" to "tissue",
        "당근" to "carrot",
        "세탁기" to "washing machine",
        "쿠키" to "cookies",
        "도마" to "cutting/chopping board",
        "테니스라켓" to "tennis racket",
        "사탕" to "candy",
        "가위" to "scissors",
        "야구공" to "baseball",
        "딸기" to "strawberry",
        "나비넥타이" to "bow tie",
        "비둘기" to "pigeon",
        "고추" to "pepper",
        "커피머신" to "coffee machine",
        "욕조" to "bathtub",
        "스노보드" to "snowboard",
        "캐리어" to "suitcase",
        "포도" to "grapes",
        "사다리" to "ladder",
        "농구공" to "basketball",
        "감자" to "potato",
        "그림붓" to "paint brush",
        "프린터" to "printer",
        "소화전" to "fire hydrant",
        "소화기" to "fire extinguisher",
        "거위" to "goose",
        "프로젝터" to "projector",
        "소시지" to "sausage",
        "멀티탭" to "extension cord",
        "테니스공" to "tennis ball",
        "젓가락" to "chopsticks",
        "파이" to "pie",
        "프리스비" to "frisbee",
        "주전자" to "kettle",
        "햄버거" to "hamburger",
        "골프채" to "golf club",
        "오이" to "cucumber",
        "믹서기" to "blender",
        "핫도그" to "hot dog",
        "칫솔" to "toothbrush",
        "망고" to "mango",
        "사슴" to "deer",
        "계란" to "egg",
        "바이올린" to "violin",
        "양파" to "onion",
        "아이스크림" to "ice cream",
        "테이프" to "tape",
        "휠체어" to "wheelchair",
        "자두" to "plum",
        "비누" to "bar soap",
        "저울" to "scale",
        "수박" to "watermelon",
        "양배추" to "cabbage",
        "공유기" to "router/modem",
        "골프공" to "golf ball",
        "파인애플" to "pine apple",
        "소방차" to "fire truck",
        "복숭아" to "peach",
        "첼로" to "cello",
        "메모지" to "notepaper",
        "세발자전거" to "tricycle",
        "토스터" to "toaster",
        "헬리콥터" to "helicopter",
        "강낭콩" to "green beans",
        "시가" to "cigar",
        "이어폰" to "earphone",
        "펭귄" to "penguin",
        "그네" to "swing",
        "라디오" to "radio",
        "백조" to "swan",
        "마늘" to "garlic",
        "감자튀김" to "french fries",
        "아보카도" to "avocado",
        "색소폰" to "saxophone",
        "트럼펫" to "trumpet",
        "샌드위치" to "sandwich",
        "키위" to "kiwi fruit",
        "낚싯대" to "fishing rod",
        "체리" to "cherry",
        "태블릿" to "tablet",
        "옥수수" to "corn",
        "열쇠" to "key",
        "드라이버" to "screwdriver",
        "지구본" to "globe",
        "빗자루" to "broom",
        "배구공" to "volleyball",
        "망치" to "hammer",
        "가지" to "eggplant",
        "트로피" to "trophy",
        "쌀밥" to "rice",
        "줄자" to "tape measure/ruler",
        "덤벨" to "dumbbell",
        "스테이플러" to "stapler",
        "낙타" to "camel",
        "상추" to "lettuce",
        "금붕어" to "goldfish",
        "메달" to "medal",
        "치약" to "toothpaste",
        "새우" to "shrimp",
        "트롬본" to "trombone",
        "석류" to "pomegranate",
        "코코넛" to "coconut",
        "해파리" to "jellyfish",
        "버섯" to "mushroom",
        "계산기" to "calculator",
        "러닝머신" to "treadmill",
        "나비" to "butterfly",
        "에그타르트" to "egg tart",
        "치즈" to "cheese",
        "돼지" to "pig",
        "밥솥" to "rice cooker",
        "튜바" to "tuba",
        "파파야" to "papaya",
        "드라이기" to "hair drier",
        "대파" to "green onion",
        "돌고래" to "dolphin",
        "초밥" to "sushi",
        "당나귀" to "donkey",
        "전동드릴" to "electric drill",
        "거북이" to "tortoise/turtle",
        "앵무새" to "parrot",
        "플루트" to "flute",
        "계량컵" to "measuring cup",
        "상어" to "shark",
        "스테이크" to "steak",
        "쌍안경" to "binoculars",
        "라마" to "llama",
        "국수" to "noodles",
        "대걸레" to "mop",
        "현미경" to "microscope",
        "바벨" to "barbell",
        "사자" to "lion",
        "북극곰" to "polar bear",
        "라이터" to "lighter",
        "물개" to "seal",
        "머리빗" to "comb",
        "지우개" to "eraser",
        "필통" to "pencil case",
        "탁구채" to "table tennis paddle",
        "불가사리" to "starfish",
        "독수리" to "eagle",
        "원숭이" to "monkey",
        "두리안" to "durian",
        "토끼" to "rabbit",
        "호른" to "french horn",
        "구급차" to "ambulance",
        "아스파라거스" to "asparagus",
        "호버보드" to "hoverboard",
        "파스타" to "pasta",
        "열기구" to "hotair balloon",
        "전기톱" to "chainsaw",
        "랍스터" to "lobster",
        "다리미" to "iron",
        "손전등" to "flashlight",

        // 1글자라 뺐던 라벨들 — 충돌 안 나는 2글자+ 대안으로 되살린 것들.
        // 완전한 동의어는 아니고(예: 젖소=cow 중 유제품용 소만 지칭) 근사치다.
        "야생곰" to "bear",
        "닭고기" to "chicken",
        "젖소" to "cow",
        "꽃게" to "crab",
        "가오리연" to "kite",
        "서양배" to "pear",
        "래디시" to "radish",
        "선박" to "ship",
        "감귤" to "tangerine",
        "들새" to "wild bird",
        // horse/saw/sheep/shovel: 자연스러운 2글자+ 대안을 못 찾아서 그대로 미커버.
    )

    /**
     * 규칙 기반으로 텍스트를 파싱해 [TargetSpec] 을 반환한다. 매칭 안 되면 기본값 유지.
     * 빈 발화는 `backend/api/session.py` 와 같은 규칙으로 status=failed 스펙이 된다.
     */
    fun parse(
        text: String,
        sessionId: String,
        source: String = "ondevice",
    ): TargetSpec {
        if (text.isBlank()) {
            return TargetSpec(
                sessionId = sessionId,
                rawText = "",
                source = source,
                schemaVersion = "0.2",
                status = TargetSpec.Status.FAILED,
            )
        }

        var subjectType = TargetSpec.SubjectType.PERSON
        var objectLabel: String? = null

        // 짧은 키워드가 더 긴 키워드의 substring인 경우가 있다 (예: "안경" ⊂ "쌍안경",
        // "넥타이" ⊂ "나비넥타이"). 선언 순서로 먼저 걸리는 걸 쓰면 "쌍안경"이 "안경"으로
        // 잘못 잡히므로, 매칭된 키워드 중 가장 긴(= 가장 구체적인) 것을 우선한다.
        val matchedKeyword = OBJECT_LABEL_KEYWORDS.keys
            .filter { it in text }
            .maxByOrNull { it.length }
        if (matchedKeyword != null) {
            subjectType = TargetSpec.SubjectType.OBJECT
            objectLabel = OBJECT_LABEL_KEYWORDS.getValue(matchedKeyword)
        } else {
            for ((keyword, candidateType) in SUBJECT_TYPE_KEYWORDS) {
                if (keyword in text) {
                    subjectType = candidateType
                    break
                }
            }
        }

        var subjectCount: Int? = null
        val digitCount = DIGIT_COUNT_PATTERN.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (digitCount != null && digitCount >= 1) {
            subjectCount = digitCount
        } else {
            for ((word, count) in COUNT_WORDS) {
                if (word in text) {
                    subjectCount = count
                    break
                }
            }
        }

        var framing = TargetSpec.Framing.FULL_BODY
        for ((keyword, candidateFraming) in FRAMING_KEYWORDS) {
            if (keyword in text) {
                framing = candidateFraming
                break
            }
        }

        val subjectMatched = subjectType != TargetSpec.SubjectType.PERSON || objectLabel != null ||
            PERSON_KEYWORDS.any { it in text }
        val countMatched = subjectCount != null
        val framingMatched = framing != TargetSpec.Framing.FULL_BODY
        val compositionMatched = COMPOSITION_KEYWORDS.any { it in text }
        val matched = listOf(subjectMatched, countMatched, framingMatched, compositionMatched)
            .count { it }
        // (40 + 20 * matched) / 100f 는 0.4/0.6/0.8/1.0 을 부동소수 누적 오차 없이 만든다.
        // 신호 4개가 다 잡혀도 1.0 상한 (Python 미러의 min(1.0, ...) 과 동일).
        val confidence = minOf(100, 40 + 20 * matched) / 100f
        val status = if (confidence >= CONFIDENCE_THRESHOLD) {
            TargetSpec.Status.OK
        } else {
            TargetSpec.Status.NEEDS_CLARIFICATION
        }

        return TargetSpec(
            sessionId = sessionId,
            rawText = text,
            source = source,
            schemaVersion = "0.2",
            status = status,
            subjectType = subjectType,
            objectLabel = objectLabel,
            subjectCount = subjectCount,
            framing = framing,
            confidence = confidence,
        )
    }
}
