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
        "통장" to TargetSpec.SubjectType.DOCUMENT,
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
        "책상" to "desk", "테이블" to "desk", "식탁" to "desk",
        "핸드백" to "handbag",
        "가로등" to "street lights",
        "구두" to "leather shoes",
        "베개" to "pillow",
        "장갑" to "glove",
        "화분" to "potted plant",
        "팔찌" to "bracelet",
        "생화" to "flower",
        "텔레비전" to "tv", "티비" to "tv",
        "꽃병" to "vase",
        "벤치" to "bench",
        "와인잔" to "wine glass",
        "부츠" to "boots",
        "그릇" to "bowl",
        "접시" to "plate",
        "우산" to "umbrella",
        "스피커" to "speaker",
        "쓰레기통" to "trash bin/can",
        "백팩" to "backpack", "가방" to "backpack",
        "소파" to "couch",
        "벨트" to "belt",
        "수건" to "towel/napkin",
        "슬리퍼" to "slippers",
        "넥타이" to "tie",
        "침대" to "bed",
        "신호등" to "traffic light",
        "연필" to "pen/pencil",
        "샌들" to "sandals",
        "목걸이" to "necklace",
        "거울" to "mirror",
        "자전거" to "bicycle",
        "하이힐" to "high heels",
        "반지" to "ring",
        "손목시계" to "watch",
        "사과" to "apple",
        "카메라" to "camera",
        "양초" to "candle",
        "곰인형" to "teddy bear",
        "인형" to "doll",
        "케이크" to "cake",
        "오토바이" to "motorcycle",
        "노트북" to "laptop",
        "나이프" to "knife",
        "표지판" to "traffic sign",
        "휴대폰" to "cell phone", "핸드폰" to "cell phone",
        "트럭" to "truck",
        "벽시계" to "clock",
        "포크" to "fork",
        "버스" to "bus",
        "냄비" to "pot/pan", "프라이팬" to "pot/pan",
        "기타" to "guitar",
        "키보드" to "keyboard",
        "선풍기" to "fan",
        "강아지" to "dog",
        "숟가락" to "spoon",
        "에어컨" to "air conditioner",
        "마우스" to "mouse",
        "오렌지" to "orange",
        "바나나" to "banana",
        "냉장고" to "refrigerator",
        "토마토" to "tomato",
        "도넛" to "donut",
        "피자" to "pizza",
        "가스레인지" to "gas stove",
        "유모차" to "stroller",
        "전자레인지" to "microwave",
        "고양이" to "cat",
        "호박" to "pumpkin",
        "피아노" to "piano",
        "티슈" to "tissue",
        "당근" to "carrot",
        "세탁기" to "washing machine",
        "쿠키" to "cookies",
        "도마" to "cutting/chopping board",
        "가위" to "scissors",
        "딸기" to "strawberry",
        "캐리어" to "suitcase",
        "포도" to "grapes",
        "감자" to "potato",
        "소화전" to "fire hydrant",
        "소화기" to "fire extinguisher",
        "젓가락" to "chopsticks",
        "주전자" to "kettle",
        "햄버거" to "hamburger",
        "골프채" to "golf club",
        "오이" to "cucumber",
        "칫솔" to "toothbrush",
        "계란" to "egg",
        "바이올린" to "violin",
        "양파" to "onion",
        "아이스크림" to "ice cream",
        "휠체어" to "wheelchair",
        "자두" to "plum",
        "수박" to "watermelon",
        "양배추" to "cabbage",
        "복숭아" to "peach",
        "마늘" to "garlic",
        "감자튀김" to "french fries",
        "샌드위치" to "sandwich",
        "태블릿" to "tablet",
        "옥수수" to "corn",
        "열쇠" to "key",
        "쌀밥" to "rice",
        "덤벨" to "dumbbell",
        "상추" to "lettuce",
        "치약" to "toothpaste",
        "밥솥" to "rice cooker",
        "드라이기" to "hair drier",
        "초밥" to "sushi",
        "스테이크" to "steak",
        "국수" to "noodles",
        "서양배" to "pear",
        "래디시" to "radish",
        "들새" to "wild bird",
        "김치" to "kimchi",
        "김밥" to "gimbap",
        "만두" to "mandu",
        "떡볶이" to "tteokbokki",
        "가래떡" to "ttoke",
        "반찬" to "side dish", "밑반찬" to "side dish",
        "수저" to "cutlery", "식기" to "cutlery",
        "국자" to "ladle",
        "밥주걱" to "rice spatula", "주걱" to "rice spatula",
        "실리콘주걱" to "silicon spatula", "뒤집개" to "silicon spatula",
        "감자칼" to "vegetable peeler", "필러" to "vegetable peeler",
        "쟁반" to "tray", "트레이" to "tray",
        "에스프레소머신" to "espresso machine", "커피머신" to "espresso machine",
        "정수기" to "purifier", "공기청정기" to "purifier",
        "변기" to "toilet bowl", "양변기" to "toilet bowl",
        "세면대" to "washstand", "세면기" to "washstand",
        "출입문" to "door", "현관문" to "door", "문짝" to "door",
        "창문" to "window",
        "지붕" to "roof",
        "간판" to "sign",
        "책자" to "book", "도서" to "book",
        "머리빗" to "hair brush", "헤어브러시" to "hair brush",
        "머플러" to "muffler", "목도리" to "muffler",
        "스케이트화" to "skating shoes", "스케이트" to "skating shoes",
        "축구공" to "ball", "농구공" to "ball", "야구공" to "ball",
        "농구골대" to "basketball hoop", "농구대" to "basketball hoop", "농구링" to "basketball hoop",
        "골대" to "goalpost", "골포스트" to "goalpost",
        "당구채" to "billiards cue", "큐대" to "billiards cue",
        "탁구채" to "table tennis racket", "탁구라켓" to "table tennis racket",
        "필라테스기구" to "pilates equipment", "필라테스" to "pilates equipment",
        "킥보드" to "scooter", "스쿠터" to "scooter",
        "드론" to "drone",
        "카라비너" to "carabiner",
        "리코더" to "recorder",
        "오카리나" to "ocarina",
        "탬버린" to "tambourine",
        "체온계" to "thermometer", "온도계" to "thermometer",
        "깻잎" to "perilla leaf",
        "대파" to "spring onion", "쪽파" to "spring onion",
        "고추" to "chili",
        "피망" to "pimento",
        "애호박" to "squash",
        "고구마" to "sweet potato",
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
