// 이 파일: 사진 검색용 "고정 라벨 사전"(assets/photo_labels.json)의 앱 쪽 로더.
// 라벨링(백엔드)과 검색(앱)이 같은 사전을 공유하는 폐쇄형 설계의 절반 —
// 발화 속 단어를 name/synonyms 로 라벨 id 로 번역한다. docs/feature-expansion-plan.md 기능 3-A.
package com.example.snap_sight.search

import org.json.JSONObject

/** 고정 사전의 라벨 1개. [synonyms] 는 "사용자가 이 라벨을 뭐라고 부를까" 목록이다. */
data class PhotoLabel(
    val id: String,
    val name: String,
    val synonyms: List<String>,
)

/**
 * 버전 있는 고정 라벨 사전. android.* 의존이 없어 JVM 단위 테스트 대상이다
 * (assets 로딩은 [com.example.snap_sight.search.PhotoSearchController] 쪽 책임).
 */
class PhotoLabelDictionary(
    val version: Int,
    val labels: List<PhotoLabel>,
) {

    private val idSet = labels.map { it.id }.toSet()

    fun contains(labelId: String): Boolean = labelId in idSet

    /**
     * 발화 텍스트에서 언급된 라벨 id 를 찾는다 — name/synonyms 가 발화에 포함되면 매칭.
     *
     * 규칙 (오매핑 방지, 2026-08-21 피드백):
     *  - 한국어는 조사가 붙어 토큰 단위 비교가 어려우므로 기본은 부분 문자열 포함이지만,
     *    **1글자 표현은 조사를 뗀 단어와 정확히 일치할 때만** 매칭 ("방"≠"가방")
     *  - **더 긴 표현 우선**: 한 라벨의 매칭 표현이 다른 라벨이 매칭한 더 긴 표현의
     *    일부라면 짧은 쪽은 버린다 — "커피숍" 발화가 카페(커피숍)와 음료(커피)에 동시에
     *    걸려 검색 조건이 과하게 좁아지는 것을 막는다
     */
    fun matchUtterance(utterance: String): Set<String> {
        if (utterance.isBlank()) return emptySet()
        val normalized = normalize(utterance)
        val tokens = wordTokens(utterance)

        // 라벨별 "가장 긴" 매칭 표현을 모은다
        val longestHit = LinkedHashMap<String, String>()
        for (label in labels) {
            val terms = sequenceOf(label.name) + label.synonyms.asSequence()
            val best = terms
                .map { normalize(it) }
                .filter { term ->
                    when {
                        term.isEmpty() -> false
                        term.length >= 2 -> normalized.contains(term)
                        else -> term in tokens
                    }
                }
                .maxByOrNull { it.length }
            if (best != null) longestHit[label.id] = best
        }

        // 더 긴 표현 우선 — 다른 라벨의 매칭 표현에 통째로 포함되는 표현은 제외
        return longestHit.filter { (labelId, term) ->
            longestHit.none { (otherId, otherTerm) ->
                otherId != labelId && otherTerm.length > term.length && otherTerm.contains(term)
            }
        }.keys
    }

    /** 발화를 단어로 쪼개고 조사를 뗀 normalized 토큰 집합 (1글자 표현 매칭용). */
    private fun wordTokens(utterance: String): Set<String> =
        utterance.split(WHITESPACE)
            .map { normalize(stripJosa(it.trim())) }
            .filter { it.isNotBlank() }
            .toSet()

    /** 매칭에 쓰인 표면형(단어)들 — 파서가 자유 검색어에서 제외할 때 쓴다. */
    fun surfaceForms(): List<String> =
        labels.flatMap { listOf(it.name) + it.synonyms }

    companion object {
        /** photo_labels.json 원문에서 사전을 만든다. 형식이 깨져 있으면 예외를 던진다. */
        fun fromJson(text: String): PhotoLabelDictionary {
            val payload = JSONObject(text)
            val version = payload.getInt("version")
            require(version >= 1) { "photo_labels.json: version 은 1 이상이어야 합니다" }
            val rawLabels = payload.getJSONArray("labels")
            require(rawLabels.length() > 0) { "photo_labels.json: labels 가 비어 있습니다" }

            val labels = ArrayList<PhotoLabel>(rawLabels.length())
            val seen = HashSet<String>()
            for (index in 0 until rawLabels.length()) {
                val entry = rawLabels.getJSONObject(index)
                val id = entry.getString("id")
                val name = entry.getString("name")
                require(id.isNotBlank() && name.isNotBlank()) { "라벨 id/name 이 비어 있습니다" }
                require(seen.add(id)) { "중복 라벨 id: $id" }
                val synonyms = entry.optJSONArray("synonyms")?.let { array ->
                    List(array.length()) { array.getString(it) }
                } ?: emptyList()
                labels.add(PhotoLabel(id = id, name = name, synonyms = synonyms))
            }
            return PhotoLabelDictionary(version = version, labels = labels)
        }

        internal fun normalize(text: String): String =
            text.trim().lowercase().replace(" ", "")

        /**
         * 흔한 조사를 뗀다 — 긴 것 먼저 (예: "카페에서" → "카페"). 파서와 사전이 공유한다.
         *
         * 원칙: 뗀 뒤 2글자 이상 남아야 한다 ("노을"의 "을"을 조사로 오인해 "노"로 만들지 않게).
         * 예외: 조사가 2글자 이상이면 1글자만 남아도 허용한다 ("집에서" → "집") —
         * 1글자 조사는 실제 단어 끝 글자와 겹치는 경우가 많지만 2글자 조사는 그럴 일이 드물다.
         */
        internal fun stripJosa(word: String): String {
            for (josa in JOSA_SUFFIXES) {
                if (!word.endsWith(josa)) continue
                val remaining = word.length - josa.length
                if (remaining >= 2 || (remaining == 1 && josa.length >= 2)) {
                    return word.dropLast(josa.length)
                }
            }
            return word
        }

        internal val WHITESPACE = Regex("\\s+")

        internal val JOSA_SUFFIXES = listOf(
            "에서", "이랑", "하고", "까지", "부터",
            "은", "는", "이", "가", "을", "를", "에", "로", "와", "과",
        )
    }
}
