package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionReadinessTest {
    private val visible = FrameVisibility(0.1f, 0.1f, 0.1f, 0.1f)

    private fun result(
        x: Float = 0f,
        y: Float? = 0f,
        size: Float = 0f,
        freshness: ObservationFreshness = ObservationFreshness.FRESH,
        ageMs: Long = 0L,
        visibility: FrameVisibility? = visible,
        framing: TargetSpec.Framing = TargetSpec.Framing.FULL_BODY,
        goal: FramingGoal? = null,
    ) = DeviationResult(
        subjectDetected = true,
        xDeviation = x,
        yDeviation = y,
        sizeDeviation = size,
        framing = framing,
        goal = goal,
        frameVisibility = visibility,
        observationFreshness = freshness,
        observationAgeMs = ageMs,
    )

    @Test
    fun `framing goal anchor is used when converting geometry`() {
        // centerY=0.42는 close-up profile의 anchorY와 정확히 일치한다.
        val geometry = FramingDeviation(
            trackId = 1,
            offsetX = 0f,
            offsetY = -0.16f,
            areaRatio = 0.30f,
            frameVisibility = visible,
        )
        val judged = DeviationJudgment.judge(geometry, TargetSpec.Framing.CLOSEUP)
        assertEquals(0f, judged.yDeviation!!, 1e-6f)
        assertTrue(DeviationJudgment.isReadyCandidate(judged))
    }

    @Test
    fun `missing or out of range y can never become ready`() {
        val missing = CompositionReadiness.candidateVerdict(result(y = null))
        assertFalse(missing.candidateReady)
        assertTrue(ReadinessBlocker.VERTICAL in missing.blockers)

        val outside = CompositionReadiness.candidateVerdict(result(y = 0.30f))
        assertFalse(outside.candidateReady)
        assertTrue(ReadinessBlocker.VERTICAL in outside.blockers)
    }

    @Test
    fun `predicted held and stale observations are not shutter ready`() {
        val predicted = CompositionReadiness.candidateVerdict(
            result(freshness = ObservationFreshness.PREDICTED, ageMs = 100L)
        )
        assertTrue(ReadinessBlocker.PREDICTED in predicted.blockers)

        val held = CompositionReadiness.candidateVerdict(
            result(freshness = ObservationFreshness.HELD, ageMs = 100L)
        )
        assertTrue(ReadinessBlocker.HELD in held.blockers)

        val stale = CompositionReadiness.candidateVerdict(result(ageMs = 451L))
        assertTrue(ReadinessBlocker.STALE in stale.blockers)
    }

    @Test
    fun `full body target touching required frame edge is not ready`() {
        val clippedTop = result(visibility = visible.copy(topMargin = 0f))
        val verdict = CompositionReadiness.candidateVerdict(clippedTop)
        assertFalse(verdict.candidateReady)
        assertTrue(ReadinessBlocker.VISIBILITY in verdict.blockers)
    }

    @Test
    fun `stability uses elapsed milliseconds and not number of frames`() {
        val evaluator = CanonicalReadinessEvaluator()
        assertFalse(evaluator.evaluate(result(), nowMs = 1_000L).ready)
        // 같은 timestamp/frame 수나 keyframe 사이 predicted 출력은 안정 시간을 가짜로 만들지 않지만,
        // 다음 fresh 관측을 위한 기존 안정 구간은 보존한다.
        repeat(20) { assertFalse(evaluator.evaluate(result(), nowMs = 1_000L).ready) }
        assertFalse(evaluator.evaluate(
            result(freshness = ObservationFreshness.PREDICTED, ageMs = 299L),
            nowMs = 1_299L,
        ).ready)
        assertTrue(evaluator.evaluate(result(), nowMs = 1_300L).ready)
    }

    @Test
    fun `ready hysteresis never overrides freshness and y safety gates`() {
        val evaluator = CanonicalReadinessEvaluator()
        evaluator.evaluate(result(), nowMs = 0L)
        assertTrue(evaluator.evaluate(result(), nowMs = 300L).ready)

        // x=0.25는 진입 0.20 밖이지만 READY 이탈 0.30 안이라 유지된다.
        assertTrue(evaluator.evaluate(result(x = 0.25f), nowMs = 350L).ready)
        // freshness와 y는 hysteresis로 무시되지 않는다.
        assertFalse(evaluator.evaluate(
            result(freshness = ObservationFreshness.PREDICTED, ageMs = 50L),
            nowMs = 400L,
        ).ready)

        assertTrue(evaluator.evaluate(result(), nowMs = 500L).ready)
        assertTrue(evaluator.evaluate(result(), nowMs = 800L).ready)
        assertFalse(evaluator.evaluate(result(y = 0.40f), nowMs = 850L).ready)
    }
}
