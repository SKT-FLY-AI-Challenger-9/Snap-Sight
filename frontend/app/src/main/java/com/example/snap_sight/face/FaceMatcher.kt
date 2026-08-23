// 이 파일: 얼굴 임베딩 vs 등록 인물 벡터들의 매칭 판정 (기능 2, 데모 최적화 방침).
// 순수 로직 — TFLite·DB·Android 를 모르며 JVM 단위 테스트 대상이다.
// 파라미터는 전부 FaceMatchConfig 한 곳에 모여 있다 — 시연 리허설에서 숫자만 바꾼다.
package com.example.snap_sight.face

import kotlin.math.sqrt

/**
 * 얼굴 매칭 파라미터 — `docs/feature-expansion-plan.md` 기능 2 파라미터 표의 코드 정본.
 *
 * @param similarityThreshold 1위 인물 점수가 이보다 낮으면 "등록 인물 아님"
 * @param margin              1위-2위 점수 차가 이보다 작으면 판정 보류 (데모 인원끼리 혼동 방지)
 * @param topK                인물별 점수 = 그 인물 벡터들과의 유사도 중 상위 k개 평균
 */
data class FaceMatchConfig(
    val similarityThreshold: Float = 0.5f,
    val margin: Float = 0.1f,
    val topK: Int = 5,
) {
    init {
        require(similarityThreshold in -1f..1f) { "similarityThreshold must be in [-1, 1]" }
        require(margin >= 0f) { "margin must be non-negative" }
        require(topK >= 1) { "topK must be at least 1" }
    }
}

object FaceMatcher {

    /**
     * 인물별 점수를 계산해 판정한다. 오인식 1번이 미인식 10번보다 치명적이므로
     * 임계값 미달·마진 미달 모두 null (침묵) 이다.
     *
     * @param embedding 후보 얼굴 임베딩 (L2 정규화 여부 무관 — cosine 이라 상관없음)
     * @param gallery   인물 이름 → 등록 임베딩 목록
     */
    fun match(
        embedding: FloatArray,
        gallery: Map<String, List<FloatArray>>,
        config: FaceMatchConfig = FaceMatchConfig(),
    ): String? = decide(rank(embedding, gallery, config.topK), config)

    /** 인물별 점수를 높은 순으로 — 디버그 로그/덤프에서 "왜 판정이 안 났는지" 보려고 분리했다. */
    fun rank(
        embedding: FloatArray,
        gallery: Map<String, List<FloatArray>>,
        topK: Int,
    ): List<Pair<String, Float>> =
        gallery.mapNotNull { (name, vectors) ->
            val score = personScore(embedding, vectors, topK) ?: return@mapNotNull null
            name to score
        }.sortedByDescending { it.second }

    /** [rank] 결과에 임계값·마진 규칙을 적용한다. 미달이면 null(침묵). */
    fun decide(ranking: List<Pair<String, Float>>, config: FaceMatchConfig): String? {
        if (ranking.isEmpty()) return null
        val (bestName, bestScore) = ranking.first()
        if (bestScore < config.similarityThreshold) return null
        val runnerUp = ranking.getOrNull(1)?.second
        if (runnerUp != null && bestScore - runnerUp < config.margin) return null
        return bestName
    }

    /** 인물 점수 = 그 인물의 전체 벡터와의 cosine 유사도 중 상위 k개 평균. */
    internal fun personScore(embedding: FloatArray, vectors: List<FloatArray>, topK: Int): Float? {
        if (vectors.isEmpty()) return null
        val similarities = vectors
            .mapNotNull { cosineSimilarity(embedding, it) }
            .sortedDescending()
        if (similarities.isEmpty()) return null
        val top = similarities.take(topK)
        return top.sum() / top.size
    }

    /** cosine 유사도. 차원이 다르거나 영벡터면 null (판정 불가). */
    internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Float? {
        if (a.size != b.size || a.isEmpty()) return null
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (index in a.indices) {
            dot += a[index] * b[index]
            normA += a[index] * a[index]
            normB += b[index] * b[index]
        }
        if (normA <= 0f || normB <= 0f) return null
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
