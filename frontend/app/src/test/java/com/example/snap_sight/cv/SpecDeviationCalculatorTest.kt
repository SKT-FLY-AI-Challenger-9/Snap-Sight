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

    /** 타겟 락은 시간 기반(hold/재획득 창) — 테스트는 가짜 시계로 결정적으로 돌린다. */
    private var nowMs = 0L
    private val calculator = SpecDeviationCalculator(clock = { nowMs })

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
        // a 가 잠깐 사라지면(hold 창 안) 멀리 있는 b 로 점프하지 않고 a 의 편차를 유지한다
        val held = calculator.compute(selection(bBigger), spec())!!
        assertEquals(1, held.trackId)
        assertTrue(held.held)
        // hold 창을 넘겨서도 a 가 없으면 그때 남은 b 로 갈아타고, 이후엔 b 가 sticky
        nowMs += TargetLockConfig().holdMs + 1
        assertEquals(2, calculator.compute(selection(bBigger), spec())!!.trackId)
        assertEquals(2, calculator.compute(selection(a, bBigger), spec())!!.trackId)
        val stats = calculator.stats()
        assertEquals(1, stats.targetSwitches)
        assertEquals(1, stats.heldFrames)
    }

    // --- 타겟 락 (기능 1-B, docs/feature-expansion-plan.md) ---

    @Test
    fun reacquiresChurnedTrackIdAtTheSamePlace() {
        // 같은 사람인데 tracker ID 만 3 → 7 로 바뀐 경우 (ID churn)
        val original = tracked(trackId = 3, xMin = 0.3f, yMin = 0.2f, xMax = 0.6f, yMax = 0.8f)
        assertEquals(3, calculator.compute(selection(original), spec())!!.trackId)
        val churned = tracked(trackId = 7, xMin = 0.32f, yMin = 0.2f, xMax = 0.62f, yMax = 0.8f)
        nowMs += 100
        // LOST 없이 새 ID 를 같은 타겟으로 이어붙인다
        val deviation = calculator.compute(selection(churned), spec())!!
        assertEquals(7, deviation.trackId)
        assertFalse(deviation.held)
        assertEquals(1, calculator.stats().reacquisitions)
        assertEquals(0, calculator.stats().targetSwitches)
    }

    @Test
    fun reacquireWindowExpiresAndFallsBackToLargest() {
        val original = tracked(trackId = 1, xMin = 0.3f, yMin = 0.2f, xMax = 0.6f, yMax = 0.8f)
        calculator.compute(selection(original), spec())
        // 재획득 창을 넘긴 뒤에는 위치가 겹쳐도 그냥 "가장 큰 후보" 규칙이다
        nowMs += TargetLockConfig().reacquireWindowMs + 1
        val far = tracked(trackId = 9, xMin = 0.0f, yMin = 0.0f, xMax = 0.5f, yMax = 0.5f)
        assertEquals(9, calculator.compute(selection(far), spec())!!.trackId)
        assertEquals(1, calculator.stats().targetSwitches)
    }

    @Test
    fun holdBridgesShortDetectionGapsThenGoesLost() {
        val target = tracked(trackId = 1, xMin = 0.3f, yMin = 0.3f, xMax = 0.7f, yMax = 0.7f)
        calculator.compute(selection(target), spec())
        // 깜빡임: 후보 없음 — hold 창 안에서는 직전 편차 유지
        nowMs += 100
        val held = calculator.compute(selection(), spec())!!
        assertTrue(held.held)
        assertEquals(1, held.trackId)
        // hold 창 만료 → LOST
        nowMs += TargetLockConfig().holdMs + 1
        assertNull(calculator.compute(selection(), spec()))
        val stats = calculator.stats()
        assertEquals(1, stats.heldFrames)
        assertEquals(1, stats.lostEpisodes)
        // LOST 가 계속돼도 에피소드는 1번만 센다
        assertNull(calculator.compute(selection(), spec()))
        assertEquals(1, calculator.stats().lostEpisodes)
    }

    @Test
    fun reacquirePrefersSameLabelCandidate() {
        val cup = TrackedObject(
            trackId = 1, label = "cup", confidence = 0.9f,
            bbox = BoundingBox(0.4f, 0.4f, 0.6f, 0.6f),
        )
        calculator.compute(selection(cup), spec(subjectType = TargetSpec.SubjectType.OBJECT))
        nowMs += 100
        // 같은 자리에 라벨이 다른 큰 후보 + 살짝 옆에 같은 라벨 후보 → 같은 라벨을 잇는다
        val bottleSamePlace = TrackedObject(
            trackId = 5, label = "bottle", confidence = 0.9f,
            bbox = BoundingBox(0.35f, 0.35f, 0.65f, 0.65f),
        )
        val cupNearby = TrackedObject(
            trackId = 6, label = "cup", confidence = 0.9f,
            bbox = BoundingBox(0.45f, 0.4f, 0.65f, 0.6f),
        )
        val deviation = calculator.compute(
            selection(bottleSamePlace, cupNearby),
            spec(subjectType = TargetSpec.SubjectType.OBJECT),
        )!!
        assertEquals(6, deviation.trackId)
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
