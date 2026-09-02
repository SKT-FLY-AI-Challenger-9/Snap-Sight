package com.example.snap_sight.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 갤러리 음성 검색의 순수 로직(사전·파서·엔진) 테스트 — 기능 3-C.
 * 시간 파싱은 가짜 now 를 주입해 결정적으로 검증한다.
 */
class PhotoSearchTest {

    private val dictionaryJson = """
        {
          "version": 3,
          "labels": [
            {"id": "food", "name": "음식", "synonyms": ["먹을 거", "밥", "요리"]},
            {"id": "nature", "name": "자연", "synonyms": ["바다", "바닷가", "산", "나무"]},
            {"id": "birthday", "name": "생일", "synonyms": ["생파", "케이크"]},
            {"id": "indoor", "name": "실내", "synonyms": ["집", "방"]},
            {"id": "night", "name": "밤", "synonyms": ["야경", "저녁", "밤에"]}
          ]
        }
    """.trimIndent()

    private val dictionary = PhotoLabelDictionary.fromJson(dictionaryJson)

    // 2026-08-21(금) 15:00 고정
    private val nowMs = Calendar.getInstance().run {
        clear(); set(2026, Calendar.AUGUST, 21, 15, 0, 0); timeInMillis
    }

    private fun parser(
        custom: List<String> = emptyList(),
        people: List<String> = emptyList(),
    ) = PhotoQueryParser(dictionary, custom, people)

    // --- 사전 ---

    @Test
    fun dictionaryMatchesNameAndSynonyms() {
        assertEquals(setOf("food"), dictionary.matchUtterance("먹을 거 찍은 사진"))
        assertEquals(setOf("food"), dictionary.matchUtterance("음식 사진 찾아줘"))
        assertEquals(setOf("nature"), dictionary.matchUtterance("바닷가에서 찍은 거"))
        assertTrue(dictionary.matchUtterance("아무 관련 없는 발화").isEmpty())
    }

    @Test
    fun singleCharSynonymMatchesOnlyWholeWords() {
        // "방"(실내 synonym)이 "가방"에 걸리면 안 된다 (2026-08-21 오매핑 수정)
        assertTrue(dictionary.matchUtterance("가방 사진 찾아줘").isEmpty())
        assertTrue(dictionary.matchUtterance("노란 가방이 나온 사진").isEmpty())
        // 단어로 말했을 때는 정상 매칭 — 조사가 붙어도 뗀 뒤 비교한다
        assertEquals(setOf("indoor"), dictionary.matchUtterance("방 사진 보여줘"))
        assertEquals(setOf("indoor"), dictionary.matchUtterance("집에서 찍은 사진"))
    }

    @Test
    fun customLabelWordDoesNotAlsoTriggerUnrelatedFixedLabel() {
        // 실사용 버그 2026-08-27: "우리 나무"로 저장한 사진은 fixed_labels가 비어 있는데(아직
        // 자동 분석 전), "나무"가 자연 라벨의 동의어이기도 해서 검색 시 자연 라벨까지 요구돼
        // 버렸다 — 커스텀 라벨로 이미 잡힌 구간은 고정 라벨 매칭에서 제외해야 한다.
        val query = parser(custom = listOf("우리 나무")).parse("우리 나무 사진 찾아줘", nowMs)
        assertEquals(setOf("우리 나무"), query.customLabels)
        assertTrue(query.labelIds.isEmpty())
        assertTrue(query.freeTerms.isEmpty())
        assertTrue(
            PhotoSearchEngine.matches(
                entry(fixedLabels = emptySet(), customUser = setOf("우리 나무"), longDesc = null), query,
            )
        )
    }

    @Test
    fun possessivePronounDoesNotBlockLabelMatch() {
        // "우리"는 필러 취급 — "나무"만 라벨(자연)로 매칭되고 "우리"가 남아 AND 조건을
        // 깨면 안 된다 (실사용 버그 2026-08-27 — "우리 나무"라고 말했는데 검색 안 됨)
        val query = parser().parse("우리 나무 사진 찾아줘", nowMs)
        assertEquals(setOf("nature"), query.labelIds)
        assertTrue(query.freeTerms.isEmpty())
        assertTrue(PhotoSearchEngine.matches(entry(fixedLabels = setOf("nature")), query))
    }

    @Test
    fun unknownBagWordFallsThroughToFreeTerms() {
        // 사전에 없는 "가방"은 라벨이 아니라 설명 본문 검색어가 된다
        val query = parser().parse("가방 사진 찾아줘", nowMs)
        assertTrue(query.labelIds.isEmpty())
        assertEquals(listOf("가방"), query.freeTerms)
        // 설명에 가방이 언급된 사진이 잡힌다
        assertTrue(
            PhotoSearchEngine.matches(
                entry(longDesc = "책상 위에 검은 가방이 놓여 있어요"), query,
            )
        )
    }

    @Test
    fun longerMatchedTermSuppressesItsSubstringLabel() {
        val json = """
            {
              "version": 2,
              "labels": [
                {"id": "drink", "name": "음료", "synonyms": ["커피"]},
                {"id": "cafe", "name": "카페", "synonyms": ["커피숍"]}
              ]
            }
        """.trimIndent()
        val dict = PhotoLabelDictionary.fromJson(json)
        // "커피숍" 발화 — 커피(음료)도 부분 매칭되지만 더 긴 "커피숍"(카페)이 이긴다
        assertEquals(setOf("cafe"), dict.matchUtterance("커피숍에서 찍은 사진"))
        // "커피"만 말하면 음료 매칭
        assertEquals(setOf("drink"), dict.matchUtterance("커피 사진 찾아줘"))
    }

    @Test
    fun shippedDictionaryMapsEverydayObjects() {
        // 실제 배포 사전(assets 동일본) 기준 — 일상 사물 발화가 의도한 라벨로 간다
        val shipped = PhotoLabelDictionary.fromJson(
            java.io.File("src/main/assets/photo_labels.json").readText()
        )
        assertEquals(setOf("bag"), shipped.matchUtterance("가방 사진 찾아줘"))
        assertEquals(setOf("bag"), shipped.matchUtterance("백팩 나온 거"))
        assertEquals(setOf("laptop"), shipped.matchUtterance("노트북 찍은 사진"))
        assertEquals(setOf("umbrella"), shipped.matchUtterance("우산 사진"))
        // "우산"의 "산"이 자연(산)으로 새지 않는다 / "책상"의 "책"이 책으로 새지 않는다
        assertEquals(setOf("furniture"), shipped.matchUtterance("책상 위 찍은 사진"))
        assertEquals(setOf("cafe"), shipped.matchUtterance("커피숍에서 찍은 사진"))
    }

    @Test
    fun dictionaryRejectsBrokenJson() {
        var rejected = false
        try {
            PhotoLabelDictionary.fromJson("""{"version": 0, "labels": []}""")
        } catch (t: Exception) {
            rejected = true
        }
        assertTrue(rejected)
    }

    // --- 질의 파서: 시간 표현 ---

    @Test
    fun parsesYesterdayAsOneDayRange() {
        val query = parser().parse("어제 찍은 사진", nowMs)
        val start = Calendar.getInstance().run {
            clear(); set(2026, Calendar.AUGUST, 20); timeInMillis
        }
        assertEquals(start, query.dateStartMs)
        assertEquals(start + 24 * 60 * 60 * 1000L, query.dateEndMs)
    }

    @Test
    fun parsesLastWeekAsMondayToMonday() {
        val query = parser().parse("지난주 사진 보여줘", nowMs)
        // 2026-08-21(금) 기준: 이번 주 월요일 = 8/17, 지난주 = 8/10 0시 ~ 8/17 0시
        val thisMonday = Calendar.getInstance().run {
            clear(); set(2026, Calendar.AUGUST, 17); timeInMillis
        }
        assertEquals(thisMonday - 7 * 24 * 60 * 60 * 1000L, query.dateStartMs)
        assertEquals(thisMonday, query.dateEndMs)
    }

    @Test
    fun parsesExplicitMonthAndResolvesFutureMonthToLastYear() {
        val august = parser().parse("8월에 찍은 사진", nowMs)
        val augustStart = Calendar.getInstance().run {
            clear(); set(2026, Calendar.AUGUST, 1); timeInMillis
        }
        assertEquals(augustStart, august.dateStartMs)

        // 12월은 아직 안 왔으므로 작년 12월로 해석
        val december = parser().parse("12월 사진", nowMs)
        val decemberStart = Calendar.getInstance().run {
            clear(); set(2025, Calendar.DECEMBER, 1); timeInMillis
        }
        assertEquals(decemberStart, december.dateStartMs)
    }

    @Test
    fun noTimeExpressionMeansNoDateRange() {
        val query = parser().parse("음식 사진", nowMs)
        assertNull(query.dateStartMs)
        assertNull(query.dateEndMs)
    }

    // --- 질의 파서: 라벨·커스텀·인물·자유어 ---

    @Test
    fun combinesTimeLabelAndCustomLabel() {
        val query = parser(custom = listOf("제주도 여행")).parse("지난주 제주도 여행에서 먹을 거 찍은 사진", nowMs)
        assertNotNull(query.dateStartMs)
        assertEquals(setOf("food"), query.labelIds)
        assertEquals(setOf("제주도 여행"), query.customLabels)
    }

    @Test
    fun matchesRegisteredPeopleNames() {
        val query = parser(people = listOf("민수", "아버지")).parse("민수 나온 사진 찾아줘", nowMs)
        assertEquals(setOf("민수"), query.people)
    }

    @Test
    fun unknownWordsBecomeFreeTermsWithoutFiller() {
        val query = parser().parse("노을 찍은 사진 찾아줘", nowMs)
        assertEquals(listOf("노을"), query.freeTerms)
    }

    @Test
    fun fillerOnlyUtteranceIsEmptyQuery() {
        val query = parser().parse("사진 찾아줘", nowMs)
        assertTrue(query.isEmpty)
    }

    // --- 검색 엔진 ---

    private fun entry(
        sessionId: String = "s_1",
        takenAtMs: Long = nowMs,
        fixedLabels: Set<String> = emptySet(),
        customUser: Set<String> = emptySet(),
        people: Set<String> = emptySet(),
        longDesc: String? = null,
    ) = PhotoIndexEntry(
        sessionId = sessionId,
        takenAtMs = takenAtMs,
        fixedLabels = fixedLabels,
        customUser = customUser,
        people = people,
        longDescription = longDesc,
    )

    @Test
    fun engineAppliesDateAndLabelTogether() {
        val yesterday = nowMs - 24 * 60 * 60 * 1000L
        val query = parser().parse("어제 찍은 음식 사진", nowMs)
        assertTrue(PhotoSearchEngine.matches(entry(takenAtMs = yesterday, fixedLabels = setOf("food")), query))
        assertFalse(PhotoSearchEngine.matches(entry(takenAtMs = yesterday), query)) // 라벨 없음
        assertFalse(PhotoSearchEngine.matches(entry(takenAtMs = nowMs, fixedLabels = setOf("food")), query)) // 오늘 찍음
    }

    @Test
    fun engineFallsBackToDescriptionForFreeTerms() {
        val query = parser().parse("노을 사진", nowMs)
        assertTrue(
            PhotoSearchEngine.matches(entry(longDesc = "붉은 노을이 지는 하늘이 보여요"), query)
        )
        assertFalse(PhotoSearchEngine.matches(entry(longDesc = "실내 카페 사진이에요"), query))
    }

    @Test
    fun filterStackNarrowsProgressively() {
        val entries = listOf(
            entry(sessionId = "a", fixedLabels = setOf("food"), people = setOf("민수")),
            entry(sessionId = "b", fixedLabels = setOf("food")),
            entry(sessionId = "c", fixedLabels = setOf("nature")),
        )
        val p = parser(people = listOf("민수"))
        val first = PhotoSearchEngine.filter(entries, listOf(p.parse("음식 사진", nowMs)))
        assertEquals(listOf("a", "b"), first.map { it.sessionId })
        // 후속 발화가 스택에 쌓여 AND 로 좁힌다
        val second = PhotoSearchEngine.filter(
            entries,
            listOf(p.parse("음식 사진", nowMs), p.parse("그중에 민수 나온 거", nowMs)),
        )
        assertEquals(listOf("a"), second.map { it.sessionId })
    }

    @Test
    fun userAttachedCustomLabelIsSearchable() {
        val query = parser(custom = listOf("제주도 여행")).parse("제주도 여행 사진", nowMs)
        assertTrue(PhotoSearchEngine.matches(entry(customUser = setOf("제주도 여행")), query))
        assertFalse(PhotoSearchEngine.matches(entry(), query))
    }

    @Test
    fun labelQueryFallsBackToDescriptionForPhotosLabeledUnderOldDictionary() {
        val dictWithBag = PhotoLabelDictionary.fromJson(
            """
            {
              "version": 2,
              "labels": [
                {"id": "bag", "name": "가방", "synonyms": ["백팩"]}
              ]
            }
            """.trimIndent()
        )
        val query = PhotoQueryParser(dictWithBag).parse("가방 사진 찾아줘", nowMs)
        assertEquals(setOf("bag"), query.labelIds)

        // 사전 v1 시절 라벨링된 사진 — bag 라벨은 없지만 설명에 가방이 언급됨
        val oldPhoto = entry(fixedLabels = setOf(), longDesc = "검은색 백팩 하나가 바닥에 세워져 있습니다")
        // dictionary 없이면 라벨 미보유로 탈락, dictionary 를 주면 본문 폴백으로 살아난다
        assertFalse(PhotoSearchEngine.matches(oldPhoto, query))
        assertTrue(PhotoSearchEngine.matches(oldPhoto, query, dictWithBag))
        // 설명에도 없는 사진은 여전히 탈락
        assertFalse(
            PhotoSearchEngine.matches(
                entry(longDesc = "책상 위 노트북 사진이에요"), query, dictWithBag,
            )
        )
    }

    @Test
    fun rollCallReadsNumberedListAndSummarizesTheRest() {
        val items = (1..7).map {
            PhotoSearchEngine.RollCallItem("8월 ${it}일", "설명 ${it}입니다. 둘째 문장.")
        }
        val text = PhotoSearchEngine.rollCall(items, maxItems = 5)
        assertTrue(text.startsWith("1번, 8월 1일, 설명 1입니다"))
        assertTrue(text.contains("5번, 8월 5일"))
        assertFalse(text.contains("6번")) // 상한 초과분은 읽지 않는다
        assertFalse(text.contains("둘째 문장")) // 첫 문장만
        assertTrue(text.endsWith("이 밖에 2장이 더 있어요"))
    }

    @Test
    fun rollCallHandlesEmptyListAndMissingDescriptions() {
        assertEquals("지금 목록에 사진이 없어요", PhotoSearchEngine.rollCall(emptyList()))
        val text = PhotoSearchEngine.rollCall(
            listOf(PhotoSearchEngine.RollCallItem("8월 20일", null))
        )
        assertEquals("1번, 8월 20일, 설명 준비 중", text)
    }

    @Test
    fun briefDescriptionTruncatesLongFirstSentence() {
        val long = "아".repeat(80) + ". 둘째 문장"
        val brief = PhotoSearchEngine.briefDescription(long)
        assertTrue(brief.length <= 41) // 40자 + 말줄임
        assertTrue(brief.endsWith("…"))
    }

    @Test
    fun announcementGuidesNarrowingWhenTooMany() {
        assertTrue(PhotoSearchEngine.announcement(0).contains("못 찾았"))
        assertEquals("1장을 찾았어요", PhotoSearchEngine.announcement(1))
        assertEquals("3장을 찾았어요", PhotoSearchEngine.announcement(3))
        assertTrue(PhotoSearchEngine.announcement(20).contains("좁혀"))
    }

    // --- 시각(하루 중 시간) 검색 (2026-08-23) ---

    private fun atHour(hour: Int, daysAgo: Int = 0): Long = Calendar.getInstance().run {
        timeInMillis = nowMs
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 30)
        timeInMillis
    }

    @Test
    fun explicitHourIn24HourFormIsParsed() {
        val query = parser().parse("18시에 찍은 사진", nowMs)
        assertEquals(listOf(18 to 19), query.hourRanges)
        assertEquals("18시", query.timePhrase)
        assertTrue(query.freeTerms.isEmpty()) // "18시에"가 검색어로 새지 않는다
        assertTrue(PhotoSearchEngine.matches(entry(takenAtMs = atHour(18)), query))
        assertFalse(PhotoSearchEngine.matches(entry(takenAtMs = atHour(10)), query))
    }

    @Test
    fun meridiemWordCorrectsTheHour() {
        assertEquals(listOf(18 to 19), parser().parse("저녁 6시 사진", nowMs).hourRanges)
        assertEquals(listOf(15 to 16), parser().parse("오후 3시에 찍은 거", nowMs).hourRanges)
        assertEquals(listOf(8 to 9), parser().parse("아침 8시 사진", nowMs).hourRanges)
        // "밤 12시" = 자정
        assertEquals(listOf(0 to 1), parser().parse("밤 12시에 찍은 사진", nowMs).hourRanges)
    }

    @Test
    fun bareAmbiguousHourMatchesBothMeridiems() {
        val query = parser().parse("6시에 찍은 사진", nowMs)
        assertEquals(listOf(6 to 7, 18 to 19), query.hourRanges)
        assertTrue(PhotoSearchEngine.matches(entry(takenAtMs = atHour(6)), query))
        assertTrue(PhotoSearchEngine.matches(entry(takenAtMs = atHour(18)), query))
        assertFalse(PhotoSearchEngine.matches(entry(takenAtMs = atHour(12)), query))
    }

    @Test
    fun timeOfDayWordAloneMatchesItsWindow() {
        val evening = parser().parse("저녁에 찍은 사진", nowMs)
        assertEquals(listOf(17 to 21), evening.hourRanges)
        // 자정을 넘는 "밤"은 두 창 — 23시와 2시 모두 잡히고 낮 12시는 아니다
        val night = parser().parse("밤에 찍은 거 보여줘", nowMs)
        assertEquals(listOf(21 to 24, 0 to 5), night.hourRanges)
        assertTrue(PhotoSearchEngine.matches(entry(takenAtMs = atHour(23)), night))
        assertTrue(PhotoSearchEngine.matches(entry(takenAtMs = atHour(2)), night))
        assertFalse(PhotoSearchEngine.matches(entry(takenAtMs = atHour(12)), night))
    }

    @Test
    fun dateAndHourConditionsCombineWithAnd() {
        val query = parser().parse("어제 저녁 6시에 찍은 사진", nowMs)
        assertNotNull(query.dateStartMs)
        assertEquals(listOf(18 to 19), query.hourRanges)
        assertEquals("어제 저녁 6시", query.timePhrase)
        assertTrue(PhotoSearchEngine.matches(entry(takenAtMs = atHour(18, daysAgo = 1)), query))
        assertFalse(PhotoSearchEngine.matches(entry(takenAtMs = atHour(10, daysAgo = 1)), query))
        assertFalse(PhotoSearchEngine.matches(entry(takenAtMs = atHour(18, daysAgo = 0)), query))
    }

    @Test
    fun hourWordsDoNotDoubleAsLabelConditions() {
        // 실기기 버그 (2026-08-23): "저녁 6시" 발화가 시각(18시)과 동시에 night 라벨(동의어 "저녁")로도
        // 매칭돼, 밤 라벨 없는 사진이 전부 걸러졌다. 시각으로 해석된 단어는 라벨이 되면 안 된다.
        val query = parser().parse("오늘 저녁 6시에 찍은 사진", nowMs)
        assertEquals(listOf(18 to 19), query.hourRanges)
        assertTrue(query.labelIds.isEmpty())
        // 오늘 18:30 사진 — night 라벨이 없어도 잡힌다
        assertTrue(PhotoSearchEngine.matches(entry(takenAtMs = atHour(18)), query, dictionary))

        val eveningOnly = parser().parse("저녁에 찍은 사진", nowMs)
        assertTrue(eveningOnly.labelIds.isEmpty())
        assertEquals(listOf(17 to 21), eveningOnly.hourRanges)

        // 시각 표현이 없으면 기존처럼 라벨 매칭 유지 ("야경 사진" = night 라벨)
        val nightLabel = parser().parse("야경 사진 찾아줘", nowMs)
        assertEquals(setOf("night"), nightLabel.labelIds)
    }

    @Test
    fun durationExpressionIsNotAnHourQuery() {
        // "N시간"은 기간이지 시각이 아니다 — 시각 조건을 만들지 않는다
        assertTrue(parser().parse("6시간 전에 찍은 사진", nowMs).hourRanges.isEmpty())
    }
}
