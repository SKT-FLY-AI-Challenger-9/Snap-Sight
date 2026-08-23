// 이 파일: 갤러리 음성 검색 발화("지난주에 찍은 음식 사진")를 구조화된 질의로 바꾸는 파서.
// 시간 표현·라벨(고정+커스텀)·인물 이름은 규칙으로, 남은 단어는 설명 본문 검색어로 넘긴다.
// 전부 온디바이스 규칙 — LLM 폴백은 커버리지 확인 후 (docs/feature-expansion-plan.md 기능 3-C).
package com.example.snap_sight.search

import java.util.Calendar

/**
 * 구조화된 사진 검색 질의 1개. 점진 필터링에서는 이 질의들이 스택에 쌓여 AND 로 적용된다.
 *
 * @param dateStartMs/[dateEndMs] 촬영 시각 범위 (endMs 는 exclusive). null = 시간 조건 없음
 * @param labelIds     고정 사전 라벨 id (전부 만족해야 함)
 * @param customLabels 커스텀 라벨 이름 (전부 만족해야 함)
 * @param people       등록 인물 이름 (전부 만족해야 함)
 * @param freeTerms    사전에 없던 검색어 — 설명 본문(long/short desc) 포함 검색으로 폴백
 * @param hourRanges   촬영 시각(시 단위) 창 목록 — [시작, 끝) 0..24, 자정을 넘는 표현("밤")은
 *                     두 창으로 나눠 담는다. 하나라도 만족하면 통과 (OR). 빈 목록 = 시각 조건 없음.
 *                     날짜 조건(dateStartMs)과는 AND — "어제 저녁 6시"가 가능하다 (2026-08-23).
 */
data class PhotoQuery(
    val rawText: String,
    val dateStartMs: Long? = null,
    val dateEndMs: Long? = null,
    val labelIds: Set<String> = emptySet(),
    val customLabels: Set<String> = emptySet(),
    val people: Set<String> = emptySet(),
    val freeTerms: List<String> = emptyList(),
    val hourRanges: List<Pair<Int, Int>> = emptyList(),
) {
    val isEmpty: Boolean
        get() = dateStartMs == null && hourRanges.isEmpty() && labelIds.isEmpty() &&
            customLabels.isEmpty() && people.isEmpty() && freeTerms.isEmpty()

    /** 사용자에게 다시 읽어줄 조건 요약 (예: "지난주, 음식"). */
    fun summary(dictionary: PhotoLabelDictionary): String {
        val parts = ArrayList<String>()
        timePhrase?.let { parts.add(it) }
        labelIds.forEach { id -> dictionary.labels.firstOrNull { it.id == id }?.let { parts.add(it.name) } }
        parts.addAll(customLabels)
        parts.addAll(people)
        parts.addAll(freeTerms)
        return parts.joinToString(", ").ifBlank { rawText }
    }

    /** 파싱된 시간 표현의 표면형 (요약용). 파서가 채운다. */
    var timePhrase: String? = null
        internal set
}

/**
 * 발화 → [PhotoQuery]. 시각([nowMs])을 주입받아 JVM 에서 결정적으로 테스트한다.
 *
 * @param dictionary   고정 라벨 사전
 * @param customLabels 사용자 커스텀 라벨 이름 목록
 * @param peopleNames  등록 인물 이름·호칭 목록 (기능 2 연동 — 없으면 빈 목록)
 */
class PhotoQueryParser(
    private val dictionary: PhotoLabelDictionary,
    private val customLabels: List<String> = emptyList(),
    private val peopleNames: List<String> = emptyList(),
) {

    fun parse(utterance: String, nowMs: Long = System.currentTimeMillis()): PhotoQuery {
        val normalized = PhotoLabelDictionary.normalize(utterance)

        val (range, datePhrase) = extractDateRange(normalized, nowMs)
        val (hourRanges, hourPhrase) = extractHourRanges(normalized)
        val timePhrase = listOfNotNull(datePhrase, hourPhrase)
            .joinToString(" ")
            .ifBlank { null }
        // 시각으로 이미 해석된 단어("저녁 6시"의 저녁)가 라벨 동의어(밤←저녁)로 이중 매칭돼
        // 엉뚱한 라벨 조건을 만들지 않게, 시각 표현을 지운 발화로 라벨을 매칭한다 (2026-08-23).
        val labelSource = if (hourPhrase == null) utterance else stripHourWords(utterance)
        val labelIds = dictionary.matchUtterance(labelSource)
        val matchedCustom = customLabels
            .filter { normalized.contains(PhotoLabelDictionary.normalize(it)) }
            .toSet()
        val matchedPeople = peopleNames
            .filter { normalized.contains(PhotoLabelDictionary.normalize(it)) }
            .toSet()

        return PhotoQuery(
            rawText = utterance.trim(),
            dateStartMs = range?.first,
            dateEndMs = range?.second,
            labelIds = labelIds,
            customLabels = matchedCustom,
            people = matchedPeople,
            freeTerms = extractFreeTerms(utterance, timePhrase, matchedCustom, matchedPeople, labelIds),
            hourRanges = hourRanges,
        ).also { it.timePhrase = timePhrase }
    }

    /** 시각 표현으로 소비된 단어(N시, 오전/오후, 시간대 단어)를 발화에서 지운다 — 라벨 매칭 전처리. */
    private fun stripHourWords(utterance: String): String {
        var cleaned = HOUR_PATTERN.replace(utterance, " ")
        (PM_WORDS + AM_WORDS + TIME_OF_DAY_RANGES.map { it.first }).forEach { word ->
            cleaned = cleaned.replace(word, " ")
        }
        return cleaned
    }

    /**
     * 시각(하루 중 시간) 표현 → 시 단위 창 목록과 표면형 (2026-08-23).
     *  - "저녁 6시"/"오후 3시": 오전·오후 단어로 보정한 한 창 [18,19)
     *  - "18시": 24시간 표기 그대로 [18,19)
     *  - "6시"(오전/오후 불명): 두 창 [6,7)+[18,19) — 어느 쪽이든 잡는다
     *  - "아침"/"저녁"/"밤" 등 시간대 단어만: 관습적 구간 (자정을 넘는 "밤"은 두 창)
     * 분 단위는 무시한다 — "5시 30분"은 5시 창에 든다. "N시간(전)"은 시각이 아니라 매칭하지 않는다.
     */
    private fun extractHourRanges(normalized: String): Pair<List<Pair<Int, Int>>, String?> {
        fun window(hour: Int): Pair<Int, Int> = (hour % 24).let { it to it + 1 }

        HOUR_PATTERN.find(normalized)?.let { match ->
            val spoken = match.groupValues[1].toInt()
            if (spoken > 24) return emptyList<Pair<Int, Int>>() to null
            val pmWord = PM_WORDS.firstOrNull { normalized.contains(it) }
            val amWord = AM_WORDS.firstOrNull { normalized.contains(it) }
            return when {
                // 13~24시는 24시간 표기 — 오전/오후 보정 불필요 (24시 = 0시)
                spoken >= 13 || spoken == 0 -> listOf(window(spoken)) to "${spoken % 24}시"
                pmWord != null -> {
                    val hour = if (spoken == 12) 0 else spoken + 12 // "밤 12시" = 자정
                    listOf(window(hour)) to "$pmWord ${spoken}시"
                }
                amWord != null -> {
                    val hour = if (spoken == 12) 0 else spoken // "오전 12시" = 자정
                    listOf(window(hour)) to "$amWord ${spoken}시"
                }
                // 오전/오후 불명 — 두 창 모두 후보
                else -> listOf(window(spoken), window(spoken + 12)) to "${spoken}시"
            }
        }

        TIME_OF_DAY_RANGES.forEach { (word, ranges) ->
            if (normalized.contains(word)) return ranges to word
        }
        return emptyList<Pair<Int, Int>>() to null
    }

    /** 시간 표현 → (시작ms, 끝ms-exclusive) 범위와 표면형. 없으면 (null, null). */
    private fun extractDateRange(normalized: String, nowMs: Long): Pair<Pair<Long, Long>?, String?> {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMs }

        fun startOfDay(daysAgo: Int): Long = (calendar.clone() as Calendar).run {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            timeInMillis
        }

        fun dayRange(daysAgo: Int): Pair<Long, Long> =
            startOfDay(daysAgo) to startOfDay(daysAgo) + DAY_MS

        // 구체적인 표현이 먼저 매칭되도록 순서 고정 ("지난주" 안에 "주"가 있어도 안전하게)
        RELATIVE_DAYS.forEach { (phrase, daysAgo) ->
            if (normalized.contains(phrase)) return dayRange(daysAgo) to phrase
        }

        if (WEEK_LAST.any { normalized.contains(it) }) {
            // 지난주 = 지난 월요일 0시 ~ 이번 주 월요일 0시
            val monday = (calendar.clone() as Calendar).run {
                firstDayOfWeek = Calendar.MONDAY
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                timeInMillis
            }
            return (monday - 7 * DAY_MS to monday) to "지난주"
        }
        if (WEEK_THIS.any { normalized.contains(it) }) {
            val monday = (calendar.clone() as Calendar).run {
                firstDayOfWeek = Calendar.MONDAY
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                timeInMillis
            }
            return (monday to monday + 7 * DAY_MS) to "이번 주"
        }

        fun monthRange(year: Int, month0: Int): Pair<Long, Long> {
            val start = Calendar.getInstance().run {
                clear(); set(year, month0, 1); timeInMillis
            }
            val end = Calendar.getInstance().run {
                clear(); set(year, month0, 1); add(Calendar.MONTH, 1); timeInMillis
            }
            return start to end
        }

        if (MONTH_LAST.any { normalized.contains(it) }) {
            val previous = (calendar.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
            return monthRange(previous.get(Calendar.YEAR), previous.get(Calendar.MONTH)) to "지난달"
        }
        if (MONTH_THIS.any { normalized.contains(it) }) {
            return monthRange(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH)) to "이번 달"
        }

        // "8월", "12월에" — 올해 기준. 아직 오지 않은 달이면 작년으로 해석한다.
        MONTH_PATTERN.find(normalized)?.let { match ->
            val month = match.groupValues[1].toInt()
            if (month in 1..12) {
                var year = calendar.get(Calendar.YEAR)
                if (month - 1 > calendar.get(Calendar.MONTH)) year -= 1
                return monthRange(year, month - 1) to "${month}월"
            }
        }

        if (YEAR_LAST.any { normalized.contains(it) }) {
            val year = calendar.get(Calendar.YEAR) - 1
            val start = Calendar.getInstance().run { clear(); set(year, 0, 1); timeInMillis }
            val end = Calendar.getInstance().run { clear(); set(year + 1, 0, 1); timeInMillis }
            return (start to end) to "작년"
        }

        return null to null
    }

    /** 시간·라벨·인물로 이미 해석된 단어와 군더더기를 빼고 남는 단어 = 설명 본문 검색어. */
    private fun extractFreeTerms(
        utterance: String,
        timePhrase: String?,
        matchedCustom: Set<String>,
        matchedPeople: Set<String>,
        labelIds: Set<String>,
    ): List<String> {
        val matchedSurfaces = buildList {
            addAll(dictionary.labels.filter { it.id in labelIds }.flatMap { listOf(it.name) + it.synonyms })
            addAll(matchedCustom)
            addAll(matchedPeople)
            timePhrase?.let { add(it) }
        }.map { PhotoLabelDictionary.normalize(it) }

        return utterance.split(PhotoLabelDictionary.WHITESPACE)
            .map { it.trim() }
            .filter { it.length >= 2 }
            // 조사 제거 전에도 filler 인지 본다 ("그중에" → 제거 후 "그중"이 되면 목록을 비껴간다)
            .filterNot { PhotoLabelDictionary.normalize(it) in FILLER_WORDS }
            .map { PhotoLabelDictionary.stripJosa(it) }
            .filter { word ->
                val normalized = PhotoLabelDictionary.normalize(word)
                normalized.isNotBlank() &&
                    normalized !in FILLER_WORDS &&
                    matchedSurfaces.none { normalized.contains(it) || it.contains(normalized) }
            }
            .distinct()
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L

        val RELATIVE_DAYS = listOf("그저께" to 2, "그제" to 2, "어제" to 1, "오늘" to 0)
        val WEEK_LAST = listOf("지난주", "저번주")
        val WEEK_THIS = listOf("이번주")
        val MONTH_LAST = listOf("지난달", "저번달")
        val MONTH_THIS = listOf("이번달")
        val YEAR_LAST = listOf("작년", "지난해")
        val MONTH_PATTERN = Regex("(\\d{1,2})월")

        // 시각 검색 (2026-08-23) — "N시" ("N시간"은 제외: 기간 표현이지 시각이 아님)
        val HOUR_PATTERN = Regex("(\\d{1,2})시(?!간)")
        val PM_WORDS = listOf("오후", "저녁", "밤")
        val AM_WORDS = listOf("오전", "아침", "새벽")
        // 시간대 단어만 있을 때의 관습적 구간 — 구체적 단어가 먼저 매칭되게 순서 고정
        val TIME_OF_DAY_RANGES = listOf(
            "새벽" to listOf(0 to 6),
            "아침" to listOf(5 to 11),
            "점심" to listOf(11 to 14),
            "저녁" to listOf(17 to 21),
            "밤" to listOf(21 to 24, 0 to 5),
            "오전" to listOf(5 to 12),
            "오후" to listOf(12 to 18),
        )

        // 검색 발화의 군더더기 — 매칭에 쓰면 안 되는 기능어 (normalize 형태로 비교)
        val FILLER_WORDS = setOf(
            "사진", "찍은", "찍었던", "촬영한", "찾아줘", "찾아", "보여줘", "보여",
            "검색", "검색해줘", "그중에", "중에", "중에서", "나온", "나오는", "있는",
            "들어간", "거", "것", "때",
        )
    }
}
