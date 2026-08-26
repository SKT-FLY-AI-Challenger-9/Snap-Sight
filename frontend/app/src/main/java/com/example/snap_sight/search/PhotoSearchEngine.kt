// 이 파일: 로컬 사진 인덱스 항목에 구조화 질의(필터 스택)를 적용하는 검색 엔진.
// 순수 로직 — DB·UI 를 모르며 JVM 단위 테스트 대상이다. docs/feature-expansion-plan.md 기능 3-C.
package com.example.snap_sight.search

/**
 * 로컬 사진 인덱스의 한 행 (Room 대신 순정 SQLite — [PhotoIndexStore] 가 저장·복원).
 *
 * @param fixedLabels  고정 사전 라벨 id (LLM 자동 부착)
 * @param customAuto   커스텀 라벨 이름 (LLM 자동 부착)
 * @param customUser   커스텀 라벨 이름 (사용자 직접 부착 — 재라벨링 시 보존 대상)
 * @param people       온디바이스 인식 인물 이름 (로컬 전용, 서버 안 거침)
 */
data class PhotoIndexEntry(
    val sessionId: String,
    val takenAtMs: Long,
    val locationText: String? = null,
    val fixedLabels: Set<String> = emptySet(),
    val customAuto: Set<String> = emptySet(),
    val customUser: Set<String> = emptySet(),
    val people: Set<String> = emptySet(),
    val shortDescription: String? = null,
    val longDescription: String? = null,
    val taxonomyVersion: Int? = null,
    /** 사진에서 읽을 만한 텍스트(메뉴판·안내문 등)를 감지했는가 — 텍스트 Q&A 안내의 트리거. */
    val hasText: Boolean = false,
    /** 감지된 텍스트가 무엇에 관한 것인지 짧은 요약 (예: "카페 메뉴판"). */
    val textTopic: String? = null,
    /** 감지된 텍스트 원문 — 결과 화면에서 후속 질문에 답할 근거. */
    val textContent: String? = null,
) {
    val allCustomLabels: Set<String> get() = customAuto + customUser
}

object PhotoSearchEngine {

    /** 필터 스택(점진 좁히기)의 모든 질의를 AND 로 적용한다. */
    fun filter(
        entries: List<PhotoIndexEntry>,
        queries: List<PhotoQuery>,
        dictionary: PhotoLabelDictionary? = null,
    ): List<PhotoIndexEntry> =
        entries.filter { entry -> queries.all { matches(entry, it, dictionary) } }

    /**
     * @param dictionary 있으면 라벨 매칭에 본문 폴백을 허용한다 — 사전이 버전업되기 전에
     *        라벨링된 사진(예: "가방" 라벨이 없던 시절)도 설명에 그 표현이 있으면 잡힌다.
     *        재라벨링(backfill)이 붙기 전까지의 안전망.
     */
    fun matches(
        entry: PhotoIndexEntry,
        query: PhotoQuery,
        dictionary: PhotoLabelDictionary? = null,
    ): Boolean {
        query.dateStartMs?.let { start ->
            if (entry.takenAtMs < start) return false
        }
        query.dateEndMs?.let { end ->
            if (entry.takenAtMs >= end) return false
        }
        if (query.hourRanges.isNotEmpty()) {
            val hour = java.util.Calendar.getInstance()
                .apply { timeInMillis = entry.takenAtMs }
                .get(java.util.Calendar.HOUR_OF_DAY)
            if (query.hourRanges.none { (start, end) -> hour in start until end }) return false
        }
        val haystack by lazy {
            PhotoLabelDictionary.normalize(
                listOfNotNull(entry.longDescription, entry.shortDescription, entry.locationText)
                    .joinToString(" "),
            )
        }
        val labelsSatisfied = query.labelIds.all { labelId ->
            labelId in entry.fixedLabels || descriptionMentionsLabel(labelId, haystack, dictionary)
        }
        if (!labelsSatisfied) return false
        if (!entry.allCustomLabels.containsAll(query.customLabels)) return false
        if (!entry.people.containsAll(query.people)) return false

        // 사전 밖 검색어는 설명 본문 포함 검색으로 폴백 — 장소 텍스트도 함께 본다
        if (query.freeTerms.isNotEmpty()) {
            val allFound = query.freeTerms.all { term ->
                haystack.contains(PhotoLabelDictionary.normalize(term))
            }
            if (!allFound) return false
        }
        return true
    }

    /** 라벨 본문 폴백 — 라벨의 name/synonyms 중 하나가 설명에 언급돼 있으면 통과. */
    private fun descriptionMentionsLabel(
        labelId: String,
        haystack: String,
        dictionary: PhotoLabelDictionary?,
    ): Boolean {
        val label = dictionary?.labels?.firstOrNull { it.id == labelId } ?: return false
        if (haystack.isBlank()) return false
        return (sequenceOf(label.name) + label.synonyms.asSequence())
            .map { PhotoLabelDictionary.normalize(it) }
            // 1글자 표현은 본문 폴백에서 제외 — "방"이 설명 속 "가방"에 걸리는 것 방지
            .filter { it.length >= 2 }
            .any { haystack.contains(it) }
    }

    /** 결과 안내 문구 — 개수와 다음 행동을 함께 알려준다 (기능 3-C 점진 좁히기 UX). */
    fun announcement(resultCount: Int, narrowingThreshold: Int = NARROWING_THRESHOLD): String = when {
        resultCount == 0 -> "조건에 맞는 사진을 못 찾았어요. 조건을 바꿔 다시 말씀해 주세요"
        resultCount > narrowingThreshold ->
            "${resultCount}장이 있어요. 언제 찍었는지, 무엇이 나오는지 더 말씀하시면 좁혀 드릴게요"
        resultCount == 1 -> "1장을 찾았어요"
        else -> "${resultCount}장을 찾았어요"
    }

    /**
     * 목록 훑어 읽기의 한 항목 — 날짜 + 짧은 설명 (없으면 자리표시).
     * @param people 등록 인물·사물 이름 (온디바이스 태그). 있으면 "유재석 나온" 을 설명 앞에 붙인다.
     */
    data class RollCallItem(
        val dateText: String,
        val description: String?,
        val people: List<String> = emptyList(),
    )

    /**
     * "지금 목록에 어떤 사진들이 있는지"를 낭독할 문구 (기능 3-C — 좁힌 결과 확인).
     * 앞에서부터 [maxItems]장만 읽고 나머지는 개수로 요약한다 — 낭독이 늘어지면
     * 시각장애 사용자가 중간에 끊을 방법이 마땅치 않기 때문.
     */
    fun rollCall(items: List<RollCallItem>, maxItems: Int = ROLL_CALL_MAX_ITEMS): String {
        if (items.isEmpty()) return "지금 목록에 사진이 없어요"
        val lines = items.take(maxItems).mapIndexed { index, item ->
            val who = if (item.people.isNotEmpty()) "${item.people.joinToString(", ")} 나온, " else ""
            "${index + 1}번, ${item.dateText}, $who${briefDescription(item.description)}"
        }
        val remainder = items.size - maxItems
        val tail = if (remainder > 0) ". 이 밖에 ${remainder}장이 더 있어요" else ""
        return lines.joinToString(separator = ". ") + tail
    }

    /** 설명의 첫 문장만, 길면 잘라서 — 훑어 읽기는 식별이 목적이지 상세 낭독이 아니다. */
    internal fun briefDescription(description: String?): String {
        val text = description?.trim().orEmpty()
        if (text.isEmpty()) return "설명 준비 중"
        val firstSentence = text.substringBefore(".").trim().ifBlank { text }
        return if (firstSentence.length <= ROLL_CALL_MAX_CHARS) firstSentence
        else firstSentence.take(ROLL_CALL_MAX_CHARS) + "…"
    }

    const val NARROWING_THRESHOLD = 8

    /** 이 개수 이하로 좁혀지면 검색 결과 안내에 목록 훑어 읽기를 자동으로 덧붙인다. */
    const val AUTO_ROLL_CALL_MAX = 5
    const val ROLL_CALL_MAX_ITEMS = 5
    internal const val ROLL_CALL_MAX_CHARS = 40
}
