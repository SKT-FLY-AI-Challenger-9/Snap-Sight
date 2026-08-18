package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tests/test_deviation.py`(Python 레퍼런스, PR #27)와 동일 시나리오로 계약 일치를 검증한다.
 * invalid framing 케이스는 Kotlin 에선 enum 이라 표현 불가능해 제외 (컴파일 타임 차단).
 */
class SpecDeviationCalculatorTest {

    private val calculator = SpecDeviationCalculator()

    private fun tracked(
        trackId: Int = 1,
        confidence: Float = 0.9f,
        xMin: Float,
        yMin: Float,
        xMax: Float,
        yMax: Float,
    ) = TrackedObject(
        trackId = trackId,
        label = "person",
        confidence = confidence,
        bbox = BoundingBox(xMin = xMin, yMin = yMin, xMax = xMax, yMax = yMax),
    )

    private fun selection(vararg objects: TrackedObject) =
        TargetSelection(state = TargetSelectionState.DISABLED, candidates = objects.toList())

    private fun spec(
        subjectType: TargetSpec.SubjectType = TargetSpec.SubjectType.PERSON,
        framing: TargetSpec.Framing = TargetSpec.Framing.FULL_BODY,
    ) = TargetSpec(
        sessionId = "s_test", rawText = "테스트", source = "ondevice",
        subjectType = subjectType, framing = framing,
    )

    // --- SpecDeviationCalculator: 기하 편차 ---

    @Test
    fun picksLargestCandidateWhenNothingIsSticky() {
        // 작은 것이 신뢰도는 더 높지만, 첫 선택은 면적 최대(가장 가깝고 두드러진 대상)
        val small = tracked(trackId = 1, confidence = 0.95f, xMin = 0f, yMin = 0f, xMax = 0.2f, yMax = 0.2f)
        // 중심 (0.5, 0.5), 면적 0.12
        val large = tracked(trackId = 2, confidence = 0.6f, xMin = 0.3f, yMin = 0.35f, xMax = 0.7f, yMax = 0.65f)
        val deviation = calculator.compute(selection(small, large), spec())!!
        assertEquals(2, deviation.trackId)
        assertEquals(0f, deviation.offsetX, 1e-5f)
        assertEquals(0.4f * 0.3f, deviation.areaRatio, 1e-5f)
    }

    @Test
    fun keepsTheSameTargetWhileItRemainsACandidate() {
        val a = tracked(trackId = 1, xMin = 0.1f, yMin = 0.1f, xMax = 0.4f, yMax = 0.6f) // 면적 0.15
        val b = tracked(trackId = 2, xMin = 0.6f, yMin = 0.1f, xMax = 0.9f, yMax = 0.5f) // 면적 0.12
        assertEquals(1, calculator.compute(selection(a, b), spec())!!.trackId)
        // 다음 프레임에 b 가 더 커져도(면적 0.24) 타겟은 a 유지 → 안내가 튀지 않는다
        val bBigger = tracked(trackId = 2, xMin = 0.5f, yMin = 0.1f, xMax = 0.9f, yMax = 0.7f)
        assertEquals(1, calculator.compute(selection(a, bBigger), spec())!!.trackId)
        // a 가 사라지면 남은 b 로 갈아타고, 이후엔 b 가 sticky (a 가 돌아와도 안내가 다시 튀지 않는다)
        assertEquals(2, calculator.compute(selection(bBigger), spec())!!.trackId)
        assertEquals(2, calculator.compute(selection(a, bBigger), spec())!!.trackId)
    }

    @Test
    fun resetForgetsTheStickyTarget() {
        val a = tracked(trackId = 1, xMin = 0.1f, yMin = 0.1f, xMax = 0.4f, yMax = 0.6f)
        val b = tracked(trackId = 2, xMin = 0.5f, yMin = 0.1f, xMax = 0.9f, yMax = 0.7f)
        assertEquals(1, calculator.compute(selection(a), spec())!!.trackId)
        calculator.reset()
        assertEquals(2, calculator.compute(selection(a, b), spec())!!.trackId)
    }

    @Test
    fun landscapeIntentReturnsNull() {
        val target = tracked(xMin = 0.3f, yMin = 0.3f, xMax = 0.7f, yMax = 0.7f)
        assertNull(calculator.compute(selection(target), spec(subjectType = TargetSpec.SubjectType.LANDSCAPE)))
    }

    @Test
    fun emptyCandidatesReturnsNull() {
        assertNull(calculator.compute(selection(), spec()))
    }

    @Test
    fun offsetIsMinusOneToOneScale() {
        // 중심 x = 0.25 → offsetX = (0.25-0.5)*2 = -0.5
        val left = tracked(xMin = 0.1f, yMin = 0.4f, xMax = 0.4f, yMax = 0.6f)
        val deviation = calculator.compute(selection(left), spec())!!
        assertEquals(-0.5f, deviation.offsetX, 1e-5f)
    }

    // --- DeviationJudgment: 판정 편차 (Python 테스트 이식) ---

    private fun geo(centerX: Float, areaRatio: Float) = FramingDeviation(
        trackId = 1, offsetX = (centerX - 0.5f) * 2f, offsetY = 0f, areaRatio = areaRatio)

    @Test
    fun centeredCorrectSizeHasZeroDeviation() {
        val result = DeviationJudgment.judge(geo(0.5f, 0.12f), TargetSpec.Framing.FULL_BODY)
        assertTrue(result.subjectDetected)
        assertEquals(0f, result.xDeviation!!, 1e-6f)
        assertEquals(0f, result.sizeDeviation!!, 1e-6f)
    }

    @Test
    fun leftOfCenterHasNegativeXDeviation() {
        val result = DeviationJudgment.judge(geo(0.3f, 0.12f), TargetSpec.Framing.FULL_BODY)
        assertEquals(-0.2f, result.xDeviation!!, 1e-5f) // 계약: center_x - 0.5
    }

    @Test
    fun rightOfCenterHasPositiveXDeviation() {
        val result = DeviationJudgment.judge(geo(0.7f, 0.12f), TargetSpec.Framing.FULL_BODY)
        assertTrue(result.xDeviation!! > 0f)
    }

    @Test
    fun smallAreaHasNegativeSizeDeviationForCloseup() {
        val result = DeviationJudgment.judge(geo(0.5f, 0.05f), TargetSpec.Framing.CLOSEUP)
        assertTrue(result.sizeDeviation!! < 0f)
    }

    @Test
    fun largeAreaHasPositiveSizeDeviationForWide() {
        val result = DeviationJudgment.judge(geo(0.5f, 0.5f), TargetSpec.Framing.WIDE)
        assertTrue(result.sizeDeviation!! > 0f)
    }

    @Test
    fun noDeviationReturnsNullFields() {
        val result = DeviationJudgment.judge(null, TargetSpec.Framing.FULL_BODY)
        assertFalse(result.subjectDetected)
        assertNull(result.xDeviation)
        assertNull(result.sizeDeviation)
    }

    // --- Kotlin 쪽 추가 계약 ---

    @Test
    fun inconsistentResultIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviationResult(subjectDetected = true, xDeviation = null, sizeDeviation = 0.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviationResult(subjectDetected = false, xDeviation = 0.1f, sizeDeviation = null)
        }
    }

    @Test
    fun readyCandidateWithinBothThresholds() {
        val ready = DeviationJudgment.judge(geo(0.52f, 0.15f), TargetSpec.Framing.FULL_BODY)
        assertTrue(DeviationJudgment.isReadyCandidate(ready))
        // x 0.20 완화: centerX 0.68 → xDeviation 0.18 → READY 후보
        val nearEdge = DeviationJudgment.judge(geo(0.68f, 0.12f), TargetSpec.Framing.FULL_BODY)
        assertTrue(DeviationJudgment.isReadyCandidate(nearEdge))

        val offCenter = DeviationJudgment.judge(geo(0.8f, 0.12f), TargetSpec.Framing.FULL_BODY)
        assertFalse(DeviationJudgment.isReadyCandidate(offCenter))

        val lost = DeviationJudgment.judge(null, TargetSpec.Framing.FULL_BODY)
        assertFalse(DeviationJudgment.isReadyCandidate(lost))
    }

    @Test
    fun targetAreaRatiosMatchInterfaceContract() {
        // docs/deviation-interface.md 의 값과 일치해야 한다
        assertEquals(0.30f, DeviationJudgment.TARGET_AREA_RATIO.getValue(TargetSpec.Framing.CLOSEUP), 1e-6f)
        assertEquals(0.12f, DeviationJudgment.TARGET_AREA_RATIO.getValue(TargetSpec.Framing.FULL_BODY), 1e-6f)
        assertEquals(0.04f, DeviationJudgment.TARGET_AREA_RATIO.getValue(TargetSpec.Framing.WIDE), 1e-6f)
    }
}
