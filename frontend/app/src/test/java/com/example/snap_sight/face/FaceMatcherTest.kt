package com.example.snap_sight.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

/**
 * 얼굴 매칭 판정(기능 2) 테스트 — top-k 평균 + 임계값 + 1위-2위 마진 규칙.
 * 오인식 방지가 목적이므로 "판정 불가 → null(침묵)" 경로를 중점 검증한다.
 */
class FaceMatcherTest {

    private fun unit(vararg values: Float): FloatArray {
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(values.size) { values[it] / norm }
    }

    private val config = FaceMatchConfig(similarityThreshold = 0.5f, margin = 0.1f, topK = 2)

    @Test
    fun matchesTheClearlyClosestPerson() {
        val gallery = mapOf(
            "민수" to listOf(unit(1f, 0f, 0f), unit(0.9f, 0.1f, 0f)),
            "아버지" to listOf(unit(0f, 1f, 0f)),
        )
        assertEquals("민수", FaceMatcher.match(unit(1f, 0.05f, 0f), gallery, config))
    }

    @Test
    fun belowThresholdIsSilent() {
        val gallery = mapOf("민수" to listOf(unit(1f, 0f, 0f)))
        // 직교 벡터 — 유사도 0
        assertNull(FaceMatcher.match(unit(0f, 0f, 1f), gallery, config))
    }

    @Test
    fun smallMarginBetweenTopTwoIsSilent() {
        // 두 인물이 거의 같은 점수 → 데모 인원끼리 혼동 방지를 위해 판정 보류
        val gallery = mapOf(
            "민수" to listOf(unit(1f, 0.1f, 0f)),
            "아버지" to listOf(unit(1f, 0f, 0.1f)),
        )
        assertNull(FaceMatcher.match(unit(1f, 0.05f, 0.05f), gallery, config))
    }

    @Test
    fun topKAveragingIgnoresOutlierVectors() {
        // 노이즈 벡터 1개(직교)가 섞여도 상위 k개 평균이라 판정이 흔들리지 않는다
        val gallery = mapOf(
            "민수" to listOf(unit(1f, 0f, 0f), unit(0.95f, 0.05f, 0f), unit(0f, 0f, 1f)),
        )
        assertEquals("민수", FaceMatcher.match(unit(1f, 0f, 0f), gallery, config))
    }

    @Test
    fun emptyGalleryOrBadVectorsAreSilent() {
        assertNull(FaceMatcher.match(unit(1f, 0f), emptyMap(), config))
        // 차원이 다른 벡터만 있는 인물은 판정 불가
        val gallery = mapOf("민수" to listOf(floatArrayOf(1f, 0f, 0f, 0f)))
        assertNull(FaceMatcher.match(unit(1f, 0f), gallery, config))
    }

    @Test
    fun registryEncodingRoundTrips() {
        val original = unit(0.3f, -0.7f, 0.64f)
        val decoded = FaceRegistry.decode(FaceRegistry.encode(original))!!
        assertEquals(original.size, decoded.size)
        for (index in original.indices) {
            assertEquals(original[index], decoded[index], 1e-6f)
        }
    }

    @Test
    fun configIsValidated() {
        var rejected = false
        try {
            FaceMatchConfig(topK = 0)
        } catch (t: IllegalArgumentException) {
            rejected = true
        }
        org.junit.Assert.assertTrue(rejected)
    }
}
