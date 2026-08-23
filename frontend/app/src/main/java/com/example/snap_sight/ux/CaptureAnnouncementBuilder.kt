package com.example.snap_sight.ux

import com.example.snap_sight.cv.TrackedObject

/** 셔터 직후 서버를 기다리지 않고, 최신 YOLO 결과의 검증된 소수 사실만 자연스럽게 확인한다. */
object CaptureAnnouncementBuilder {
    data class Lexeme(val korean: String, val counter: String = "개")

    data class Input(
        val objects: List<TrackedObject>,
        val identities: Map<Int, String> = emptyMap(),
        val registeredPeople: Set<String> = emptySet(),
        val stableFrames: Map<Int, Int> = emptyMap(),
        val sourceAgeMs: Long,
        val lexicon: Map<String, Lexeme> = DEFAULT_LEXICON,
    )

    fun build(input: Input): String {
        if (input.sourceAgeMs !in 0..MAX_SOURCE_AGE_MS) return FALLBACK
        val reliable = dedupe(
            input.objects.filter { item ->
                !item.predicted &&
                    item.observationAgeMs <= MAX_OBSERVATION_AGE_MS &&
                    item.confidence >= confidenceThreshold(item.label) &&
                    item.bbox.area >= minimumArea(item.label)
            }
        )
        if (reliable.isEmpty()) return FALLBACK

        val reliableIds = reliable.mapTo(HashSet()) { it.trackId }
        val verifiedNames = input.identities
            .filterKeys { it in reliableIds }
            .values
            .distinct()
            .map { if (it in input.registeredPeople) "${it}님" else it }

        val namedTrackIds = input.identities.filterKeys { it in reliableIds }.keys
        val unnamed = reliable.filterNot { it.trackId in namedTrackIds }
        val peopleCount = unnamed.count { normalized(it.label) == "person" }
        val objectFacts = unnamed
            .filterNot { normalized(it.label) == "person" }
            .mapNotNull { item -> input.lexicon[normalized(item.label)]?.let { item to it } }
            .groupBy { normalized(it.first.label) }
            .values
            .sortedByDescending { group -> group.maxOf { it.first.bbox.area * it.first.confidence } }
            .take(MAX_OBJECT_KINDS)
            .map { group -> counted(group.first().second, group.size) }

        val facts = buildList {
            addAll(verifiedNames)
            if (peopleCount > 0) {
                add(
                    if (verifiedNames.isEmpty()) "사람 ${nativeNumber(peopleCount)} 명"
                    else "다른 사람 ${nativeNumber(peopleCount)} 명"
                )
            }
            addAll(objectFacts)
        }
        if (facts.isEmpty()) return FALLBACK
        val joined = joinWithAnd(facts)
        return "촬영했어요. $joined${objectParticle(joined)} 확인했어요."
    }

    fun topicParticle(word: String): String = if (hasFinalConsonant(word)) "은" else "는"

    private fun dedupe(objects: List<TrackedObject>): List<TrackedObject> {
        val kept = ArrayList<TrackedObject>(objects.size)
        for (candidate in objects.sortedByDescending { it.confidence }) {
            if (kept.none {
                    normalized(it.label) == normalized(candidate.label) &&
                        it.bbox.iou(candidate.bbox) >= DUPLICATE_IOU
                }) {
                kept += candidate
            }
        }
        return kept
    }

    private fun counted(lexeme: Lexeme, count: Int): String =
        if (count <= 1) lexeme.korean else "${lexeme.korean} ${nativeNumber(count)} ${lexeme.counter}"

    private fun joinWithAnd(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts.first()
        2 -> "${parts[0]}${andParticle(parts[0])} ${parts[1]}"
        else -> parts.dropLast(1).joinToString(", ") +
            andParticle(parts[parts.lastIndex - 1]) + " ${parts.last()}"
    }

    private fun andParticle(word: String): String = if (hasFinalConsonant(word)) "과" else "와"
    private fun objectParticle(word: String): String = if (hasFinalConsonant(word)) "을" else "를"

    private fun hasFinalConsonant(word: String): Boolean {
        val character = word.lastOrNull { !it.isWhitespace() } ?: return false
        return character.code in 0xAC00..0xD7A3 && (character.code - 0xAC00) % 28 != 0
    }

    private fun nativeNumber(value: Int): String = when (value) {
        1 -> "한"
        2 -> "두"
        3 -> "세"
        4 -> "네"
        5 -> "다섯"
        6 -> "여섯"
        7 -> "일곱"
        8 -> "여덟"
        9 -> "아홉"
        10 -> "열"
        else -> value.toString()
    }

    private fun normalized(label: String): String = label.trim().lowercase()
    private fun confidenceThreshold(label: String): Float = if (normalized(label) == "person") 0.4f else 0.35f
    private fun minimumArea(label: String): Float = if (normalized(label) == "person") 0.006f else 0.004f

    const val FALLBACK = "촬영했어요. 화면에서 피사체를 확실히 구분하지 못했어요."
    // 열 제한 시 detector 간격이 600ms 이상으로 늘어도 마지막 실제 관측으로 즉시 설명할 수 있게 한다.
    private const val MAX_SOURCE_AGE_MS = 1_500L
    private const val MAX_OBSERVATION_AGE_MS = 500L
    private const val MAX_OBJECT_KINDS = 3
    private const val DUPLICATE_IOU = 0.55f

    val DEFAULT_LEXICON = mapOf(
        "person" to Lexeme("사람", "명"), "sneakers" to Lexeme("운동화"),
        "chair" to Lexeme("의자"), "hat" to Lexeme("모자"), "lamp" to Lexeme("조명"),
        "bottle" to Lexeme("병"), "cabinet/shelf" to Lexeme("수납장"), "cup" to Lexeme("컵"),
        "car" to Lexeme("자동차", "대"), "glasses" to Lexeme("안경"),
        "picture/frame" to Lexeme("액자"), "desk" to Lexeme("책상"), "handbag" to Lexeme("가방"),
        "street lights" to Lexeme("가로등"), "book" to Lexeme("책", "권"),
        "plate" to Lexeme("접시"), "helmet" to Lexeme("헬멧"), "leather shoes" to Lexeme("구두"),
        "pillow" to Lexeme("베개"), "glove" to Lexeme("장갑"), "potted plant" to Lexeme("화분"),
        "flower" to Lexeme("꽃", "송이"), "tv" to Lexeme("텔레비전"),
        "storage box" to Lexeme("수납 상자"), "vase" to Lexeme("꽃병"), "bench" to Lexeme("벤치"),
        "wine glass" to Lexeme("와인잔"), "boots" to Lexeme("부츠"), "bowl" to Lexeme("그릇"),
        "dining table" to Lexeme("식탁"), "umbrella" to Lexeme("우산"), "boat" to Lexeme("보트"),
        "speaker" to Lexeme("스피커"), "trash bin/can" to Lexeme("쓰레기통"),
        "backpack" to Lexeme("백팩"), "couch" to Lexeme("소파"), "basket" to Lexeme("바구니"),
        "towel/napkin" to Lexeme("수건"), "slippers" to Lexeme("슬리퍼"),
        "coffee table" to Lexeme("탁자"), "toy" to Lexeme("장난감"), "bed" to Lexeme("침대"),
        "pen/pencil" to Lexeme("필기구"), "microphone" to Lexeme("마이크"),
        "mirror" to Lexeme("거울"), "bicycle" to Lexeme("자전거", "대"), "bread" to Lexeme("빵"),
        "watch" to Lexeme("시계"), "sink" to Lexeme("싱크대"), "camera" to Lexeme("카메라"),
        "candle" to Lexeme("촛불"), "teddy bear" to Lexeme("곰 인형"), "cake" to Lexeme("케이크"),
        "laptop" to Lexeme("노트북"), "knife" to Lexeme("칼"), "cell phone" to Lexeme("휴대폰"),
        "truck" to Lexeme("트럭", "대"), "clock" to Lexeme("시계"), "fork" to Lexeme("포크"),
        "bus" to Lexeme("버스", "대"), "hanger" to Lexeme("옷걸이"), "pot/pan" to Lexeme("냄비"),
        "guitar" to Lexeme("기타"), "tea pot" to Lexeme("주전자"), "keyboard" to Lexeme("키보드"),
        "fan" to Lexeme("선풍기"), "dog" to Lexeme("강아지", "마리"), "spoon" to Lexeme("숟가락"),
        "balloon" to Lexeme("풍선"), "air conditioner" to Lexeme("에어컨"),
        "mouse" to Lexeme("마우스"), "telephone" to Lexeme("전화기"),
        "apple" to Lexeme("사과"), "orange" to Lexeme("오렌지"), "banana" to Lexeme("바나나"),
        "pizza" to Lexeme("피자"), "refrigerator" to Lexeme("냉장고"),
        "cat" to Lexeme("고양이", "마리"), "monitor/tv" to Lexeme("모니터"),
        "handbag/satchel" to Lexeme("가방"), "bowl/basin" to Lexeme("그릇"),
    )
}
