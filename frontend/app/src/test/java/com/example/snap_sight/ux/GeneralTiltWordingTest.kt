package com.example.snap_sight.ux

import com.example.snap_sight.cv.DeviationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 일반 세션 기울기 문구 (2026-08-25) — 수직 이동 안내("위로/아래로")의 원인이 폰 피치로
 * 보이면([GuidancePolicy.PITCH_WORDING_SWAP_DEG] 초과, 교정이 수평 쪽) "폰 윗부분을 …
 * 기울여 주세요"로 바꿔 말하고, 아니면 기존 이동 문구를 유지하는지 검증.
 */
class GeneralTiltWordingTest {

    private fun result(x: Float = 0f, size: Float = 0f, y: Float? = 0f) = DeviationResult(
        subjectDetected = true,
        xDeviation = x,
        sizeDeviation = size,
        yDeviation = y,
    )

    private fun GuidancePolicy.feed(r: DeviationResult, now: Long, phonePitch: Float?) =
        onJudgment(GuidanceStateMapper.from(r), r, now, phonePitchDeg = phonePitch)

    private fun speech(actions: List<GuidanceAction>) =
        actions.filterIsInstance<GuidanceAction.Speak>().map { it.text }

    @Test
    fun `subject high with camera tilted down speaks tilt-toward wording`() {
        // 피사체가 프레임 위 + 카메라가 아래를 봄(양수 피치) → 기울기가 원인
        val actions = GuidancePolicy().feed(result(y = -0.28f), now = 0, phonePitch = 30f)
        assertEquals(listOf(GuidanceDirection.TILT_TOP_TOWARD.utterance), speech(actions))
        assertTrue(actions.contains(GuidanceAction.Vibrate))
    }

    @Test
    fun `subject low with camera tilted up speaks tilt-away wording`() {
        val actions = GuidancePolicy().feed(result(y = 0.28f), now = 0, phonePitch = -30f)
        assertEquals(listOf(GuidanceDirection.TILT_TOP_AWAY.utterance), speech(actions))
    }

    @Test
    fun `pitch below swap threshold keeps the move wording`() {
        val actions = GuidancePolicy().feed(result(y = -0.28f), now = 0, phonePitch = 5f)
        assertEquals(listOf(GuidanceDirection.Clock(12).utterance), speech(actions))
    }

    @Test
    fun `correction away from level keeps the move wording`() {
        // 피사체가 위인데 카메라도 이미 위를 봄(음수 피치) — 선반 위 물건 등. 기울기 탓이
        // 아니므로 "위로 이동"을 유지한다 (더 기울이라고 하면 수평에서 멀어진다).
        val actions = GuidancePolicy().feed(result(y = -0.28f), now = 0, phonePitch = -30f)
        assertEquals(listOf(GuidanceDirection.Clock(12).utterance), speech(actions))
    }

    @Test
    fun `null pitch keeps existing behaviour untouched`() {
        val actions = GuidancePolicy().feed(result(y = -0.28f), now = 0, phonePitch = null)
        assertEquals(listOf(GuidanceDirection.Clock(12).utterance), speech(actions))
    }

    @Test
    fun `horizontal deviation is not affected by pitch`() {
        // 수평 축이 이길 때는 피치가 커도 좌우 문구 그대로 — 교체는 수직 안내에만 관여한다
        val actions = GuidancePolicy().feed(result(x = -0.60f, y = 0f), now = 0, phonePitch = 30f)
        assertEquals(listOf(GuidanceDirection.Clock(10).utterance), speech(actions))
    }

    @Test
    fun `food pitch branch still wins over general tilt wording`() {
        // 음식 세션(피치 편차 공급)에서는 45° 목표 분기가 먼저다 — MainActivity 도 이 경우
        // phonePitch 를 null 로 주지만, 둘 다 들어와도 순서가 지켜지는지 방어적으로 확인
        val r = result(y = -0.28f)
        val actions = GuidancePolicy().onJudgment(
            GuidanceStateMapper.from(r), r, 0,
            pitchDeviationDeg = 30f, phonePitchDeg = 30f,
        )
        assertEquals(listOf(GuidanceDirection.TILT_LAY.utterance), speech(actions))
    }

    @Test
    fun `tilt wording repeats only after the repeat interval`() {
        val policy = GuidancePolicy()
        policy.feed(result(y = -0.28f), now = 0, phonePitch = 30f)
        assertTrue(policy.feed(result(y = -0.28f), now = 500, phonePitch = 30f).isEmpty())
        val again = policy.feed(
            result(y = -0.28f),
            now = GuidancePolicy.DIRECTION_REPEAT_MS,
            phonePitch = 30f,
        )
        assertEquals(listOf(GuidanceDirection.TILT_TOP_TOWARD.utterance), speech(again))
    }

    @Test
    fun `leveling the phone switches back to the move wording after the gap`() {
        val policy = GuidancePolicy()
        policy.feed(result(y = -0.28f), now = 0, phonePitch = 30f)
        // 사용자가 폰을 세웠지만 피사체가 아직 위 — 기울기 문구에서 이동 문구로 전환
        val leveled = policy.feed(
            result(y = -0.28f),
            now = GuidancePolicy.DIRECTION_MIN_GAP_MS,
            phonePitch = 0f,
        )
        assertEquals(listOf(GuidanceDirection.Clock(12).utterance), speech(leveled))
    }
}
