package com.example.snap_sight.ux

import com.example.snap_sight.cv.DeviationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 음식 피치 안내 (2026-08-25) — 폰 각도 편차가 [GuidancePolicy.PITCH_TOLERANCE_DEG]를
 * 넘으면 구도 안내보다 먼저 각도 안내가 나가고, 허용 오차 안이면 기존 동작 그대로인지 검증.
 */
class FoodPitchGuidanceTest {

    private fun result(x: Float, size: Float, y: Float? = 0f) = DeviationResult(
        subjectDetected = true,
        xDeviation = x,
        sizeDeviation = size,
        yDeviation = y,
    )

    private fun GuidancePolicy.feed(r: DeviationResult, now: Long, pitch: Float?) =
        onJudgment(GuidanceStateMapper.from(r), r, now, pitchDeviationDeg = pitch)

    private fun speech(actions: List<GuidanceAction>) =
        actions.filterIsInstance<GuidanceAction.Speak>().map { it.text }

    @Test
    fun `pitch off target speaks tilt guidance before composition directions`() {
        // x 편차가 커도(왼쪽 안내감) 피치가 목표를 벗어나 있으면 각도부터 말한다
        val actions = GuidancePolicy().feed(result(x = -0.30f, size = 0f), now = 0, pitch = 30f)
        assertEquals(listOf(GuidanceDirection.TILT_LAY.utterance), speech(actions))
        assertTrue(actions.contains(GuidanceAction.Vibrate))
    }

    @Test
    fun `negative deviation asks to raise the phone`() {
        val actions = GuidancePolicy().feed(result(x = 0f, size = 0f), now = 0, pitch = -25f)
        assertEquals(listOf(GuidanceDirection.TILT_RAISE.utterance), speech(actions))
    }

    @Test
    fun `pitch within tolerance falls through to normal direction guidance`() {
        val actions = GuidancePolicy().feed(result(x = -0.30f, size = 0f), now = 0, pitch = 5f)
        assertEquals(listOf(GuidanceDirection.LEFT.utterance), speech(actions))
    }

    @Test
    fun `null pitch keeps existing behaviour untouched`() {
        val actions = GuidancePolicy().feed(result(x = -0.30f, size = 0f), now = 0, pitch = null)
        assertEquals(listOf(GuidanceDirection.LEFT.utterance), speech(actions))
    }

    @Test
    fun `tilt guidance repeats only after the repeat interval`() {
        val policy = GuidancePolicy()
        policy.feed(result(x = 0f, size = 0f), now = 0, pitch = 30f)
        assertTrue(policy.feed(result(x = 0f, size = 0f), now = 500, pitch = 30f).isEmpty())
        val again = policy.feed(
            result(x = 0f, size = 0f),
            now = GuidancePolicy.DIRECTION_REPEAT_MS,
            pitch = 30f,
        )
        assertEquals(listOf(GuidanceDirection.TILT_LAY.utterance), speech(again))
    }

    @Test
    fun `tilt direction flip is spoken after the minimum gap`() {
        val policy = GuidancePolicy()
        policy.feed(result(x = 0f, size = 0f), now = 0, pitch = 30f)
        // 방향이 바뀌어도(눕혀→세워) 최소 간격 전에는 침묵, 지나면 새 방향
        assertTrue(policy.feed(result(x = 0f, size = 0f), now = 400, pitch = -30f).isEmpty())
        val flipped = policy.feed(
            result(x = 0f, size = 0f),
            now = GuidancePolicy.DIRECTION_MIN_GAP_MS,
            pitch = -30f,
        )
        assertEquals(listOf(GuidanceDirection.TILT_RAISE.utterance), speech(flipped))
    }
}
