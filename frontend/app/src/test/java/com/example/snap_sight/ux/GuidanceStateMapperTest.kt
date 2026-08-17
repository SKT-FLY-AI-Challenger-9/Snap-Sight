package com.example.snap_sight.ux

import com.example.snap_sight.cv.DeviationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GuidanceStateMapper] 임계값·경계값 검증. landscape 판정(subjectType)은 이 레이어에 들어오지
 * 않으므로(알려진 interface blocker, GuidanceFeedback.kt 참고) 여기서는 다루지 않는다 —
 * non-landscape 세션에서 [DeviationResult] → [GuidanceState] 변환만 검증한다.
 */
class GuidanceStateMapperTest {

    private fun result(x: Float, size: Float) =
        DeviationResult(subjectDetected = true, xDeviation = x, sizeDeviation = size)

    // --- 미탐지 ---

    @Test
    fun undetectedMapsToDetectedFalseWithNullFields() {
        val state = GuidanceStateMapper.from(
            DeviationResult(subjectDetected = false, xDeviation = null, sizeDeviation = null)
        )
        assertFalse(state.detected)
        assertNull(state.horizontal)
        assertNull(state.distance)
        assertFalse(state.isReady)
    }

    // --- 수평 축 경계값 ---

    @Test
    fun xExactlyAtNegativeThresholdIsCentered() {
        // 조건이 `<` 이므로 경계값 자체는 CENTERED
        val state = GuidanceStateMapper.from(result(x = -0.1f, size = 0f))
        assertEquals(HorizontalAlignment.CENTERED, state.horizontal)
    }

    @Test
    fun xExactlyAtPositiveThresholdIsCentered() {
        val state = GuidanceStateMapper.from(result(x = 0.1f, size = 0f))
        assertEquals(HorizontalAlignment.CENTERED, state.horizontal)
    }

    @Test
    fun xJustBeyondNegativeThresholdIsLeft() {
        val state = GuidanceStateMapper.from(result(x = -0.1001f, size = 0f))
        assertEquals(HorizontalAlignment.LEFT, state.horizontal)
    }

    @Test
    fun xJustBeyondPositiveThresholdIsRight() {
        val state = GuidanceStateMapper.from(result(x = 0.1001f, size = 0f))
        assertEquals(HorizontalAlignment.RIGHT, state.horizontal)
    }

    @Test
    fun xAtZeroIsCentered() {
        val state = GuidanceStateMapper.from(result(x = 0f, size = 0f))
        assertEquals(HorizontalAlignment.CENTERED, state.horizontal)
    }

    // --- 거리 축 경계값 ---

    @Test
    fun sizeExactlyAtNegativeThresholdIsCentered() {
        val state = GuidanceStateMapper.from(result(x = 0f, size = -0.05f))
        assertEquals(DistanceAlignment.CENTERED, state.distance)
    }

    @Test
    fun sizeExactlyAtPositiveThresholdIsCentered() {
        val state = GuidanceStateMapper.from(result(x = 0f, size = 0.05f))
        assertEquals(DistanceAlignment.CENTERED, state.distance)
    }

    @Test
    fun sizeJustBelowNegativeThresholdIsCloser() {
        // size_deviation 음수 = 목표보다 작음(=멀다)가 아니라, 계약상 음수는 "너무 멂" — CLOSER 판정 기준값 아래
        val state = GuidanceStateMapper.from(result(x = 0f, size = -0.0501f))
        assertEquals(DistanceAlignment.CLOSER, state.distance)
    }

    @Test
    fun sizeJustAboveThresholdIsFarther() {
        val state = GuidanceStateMapper.from(result(x = 0f, size = 0.0501f))
        assertEquals(DistanceAlignment.FARTHER, state.distance)
    }

    // --- isReady 조합 ---

    @Test
    fun readyOnlyWhenBothAxesCentered() {
        val ready = GuidanceStateMapper.from(result(x = 0f, size = 0f))
        assertTrue(ready.isReady)

        val offHorizontal = GuidanceStateMapper.from(result(x = 0.2f, size = 0f))
        assertFalse(offHorizontal.isReady)

        val offDistance = GuidanceStateMapper.from(result(x = 0f, size = 0.2f))
        assertFalse(offDistance.isReady)

        val offBoth = GuidanceStateMapper.from(result(x = 0.2f, size = -0.2f))
        assertFalse(offBoth.isReady)
    }

    @Test
    fun detectedIsTrueWheneverSubjectDetected() {
        val state = GuidanceStateMapper.from(result(x = 0.3f, size = 0.3f))
        assertTrue(state.detected)
    }
}
