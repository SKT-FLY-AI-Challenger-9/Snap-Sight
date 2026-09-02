package com.example.snap_sight.ux

import com.example.snap_sight.camera.PhoneRoll
import com.example.snap_sight.cv.DeviationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 인물·사물 세션의 좌우 수평 안내 (2026-08-30, 엔드유저 피드백 "폰이 좌우로 기울어진 경우") —
 * 풍경 모드 [LandscapeGuide] 와 같은 규약(스냅 편차, 6°/2.5° 히스테리시스, 같은 문구, 복귀
 * 확인)에 1초 디바운스를 더한 것. 위치 안내보다 먼저 나가고, 수평으로 돌아오면 기존 안내로
 * 복귀하는지 검증.
 */
class PhoneRollGuidanceTest {

    private fun result(x: Float, size: Float = 0f, y: Float? = 0f) = DeviationResult(
        subjectDetected = true,
        xDeviation = x,
        sizeDeviation = size,
        yDeviation = y,
    )

    private fun GuidancePolicy.feed(r: DeviationResult, now: Long, roll: Float?) =
        onJudgment(GuidanceStateMapper.from(r), r, now, phoneRollDeg = roll)

    private fun speech(actions: List<GuidanceAction>) =
        actions.filterIsInstance<GuidanceAction.Speak>().map { it.text }

    private val debounce = GuidancePolicy.ROLL_DEBOUNCE_MS

    @Test
    fun `roll must persist for the debounce before it is spoken`() {
        val policy = GuidancePolicy()
        // 아직 1초가 안 됐으므로 기존 위치 안내가 그대로 나간다
        val first = policy.feed(result(x = 0.30f), now = 0, roll = -12f)
        assertEquals(listOf(GuidanceDirection.Clock(1).utterance), speech(first))
        // 1초 이어지면 수평 문구가 위치 안내를 제치고 나간다 (음성 + 진동)
        val spoken = policy.feed(result(x = 0.30f), now = debounce, roll = -12f)
        assertEquals(listOf(GuidanceDirection.ROLL_TURN_LEFT.utterance), speech(spoken))
        assertTrue(spoken.contains(GuidanceAction.Vibrate))
    }

    @Test
    fun `sign follows the landscape convention - positive roll turns right, negative turns left`() {
        // 실기기 확정 (2026-08-28): 폰을 왼쪽(반시계)으로 돌리면 roll 이 + → 오른쪽으로 되돌리기
        val positive = GuidancePolicy()
        positive.feed(result(x = 0f), now = 0, roll = 15f)
        assertEquals(
            listOf(GuidanceDirection.ROLL_TURN_RIGHT.utterance),
            speech(positive.feed(result(x = 0f), now = debounce, roll = 15f)),
        )
        val negative = GuidancePolicy()
        negative.feed(result(x = 0f), now = 0, roll = -15f)
        assertEquals(
            listOf(GuidanceDirection.ROLL_TURN_LEFT.utterance),
            speech(negative.feed(result(x = 0f), now = debounce, roll = -15f)),
        )
    }

    @Test
    fun `phrases and thresholds are shared with the landscape guide`() {
        assertEquals(LandscapeGuide.ROLL_TURN_LEFT_UTTERANCE, GuidanceDirection.ROLL_TURN_LEFT.utterance)
        assertEquals(LandscapeGuide.ROLL_TURN_RIGHT_UTTERANCE, GuidanceDirection.ROLL_TURN_RIGHT.utterance)
        assertEquals(LandscapeGuide.LEVEL_UTTERANCE, GuidancePolicy.LEVEL_UTTERANCE)
        assertEquals(LandscapeGuide.ROLL_ENTER_DEG, PhoneRoll.ENTER_DEG, 1e-6f)
        assertEquals(LandscapeGuide.ROLL_EXIT_DEG, PhoneRoll.EXIT_DEG, 1e-6f)
    }

    @Test
    fun `a brief tilt that ends before the debounce is ignored`() {
        val policy = GuidancePolicy()
        policy.feed(result(x = 0.30f), now = 0, roll = -12f)
        policy.feed(result(x = 0.30f), now = 500, roll = -1f) // 돌아옴 — 타이머 리셋
        val later = policy.feed(result(x = 0.30f), now = debounce, roll = -12f)
        // 1000ms 지점에서 새로 시작한 것이므로 아직 수평 문구가 아니다
        assertTrue(speech(later).none { it == GuidanceDirection.ROLL_TURN_LEFT.utterance })
    }

    @Test
    fun `once active the guidance holds until the exit threshold then confirms level`() {
        val policy = GuidancePolicy()
        policy.feed(result(x = 0.30f), now = 0, roll = -12f)
        policy.feed(result(x = 0.30f), now = debounce, roll = -12f)
        // 4° — 진입(6°)보다는 작지만 해제(2.5°)보다는 커서 계속 수평 문구 (반복 간격 뒤)
        val t1 = debounce + GuidancePolicy.DIRECTION_REPEAT_MS
        assertEquals(
            listOf(GuidanceDirection.ROLL_TURN_LEFT.utterance),
            speech(policy.feed(result(x = 0.30f), now = t1, roll = -4f)),
        )
        // 1° — 해제 아래로 내려오면 "수평이 맞았어요" 1회
        val t2 = t1 + 200
        assertEquals(listOf(GuidancePolicy.LEVEL_UTTERANCE), speech(policy.feed(result(x = 0.30f), now = t2, roll = -1f)))
        // 그 뒤 최소 간격이 지나면 기존 위치 안내로 복귀, 수평 확인은 반복되지 않는다
        val t3 = t2 + GuidancePolicy.DIRECTION_MIN_GAP_MS
        assertEquals(listOf(GuidanceDirection.Clock(1).utterance), speech(policy.feed(result(x = 0.30f), now = t3, roll = -1f)))
    }

    @Test
    fun `roll between exit and enter thresholds does not start guidance`() {
        val policy = GuidancePolicy()
        policy.feed(result(x = 0.30f), now = 0, roll = -4f)
        val later = policy.feed(result(x = 0.30f), now = GuidancePolicy.DIRECTION_REPEAT_MS, roll = -4f)
        assertEquals(listOf(GuidanceDirection.Clock(1).utterance), speech(later))
    }

    @Test
    fun `landscape grip near 90 degrees counts as level`() {
        // 가로 파지도 정상 자세 — 90° 근처에서는 수평 안내가 없어야 한다 (실기기 2026-08-28)
        val policy = GuidancePolicy()
        policy.feed(result(x = 0.30f), now = 0, roll = 90f)
        val held = policy.feed(result(x = 0.30f), now = GuidancePolicy.DIRECTION_REPEAT_MS, roll = -88f)
        assertEquals(listOf(GuidanceDirection.Clock(1).utterance), speech(held))
        // 90° 스냅에서 10° 지나침(100° = 왼쪽으로 과회전) → 오른쪽으로 되돌리기
        val over = GuidancePolicy()
        over.feed(result(x = 0.30f), now = 0, roll = 100f)
        assertEquals(
            listOf(GuidanceDirection.ROLL_TURN_RIGHT.utterance),
            speech(over.feed(result(x = 0.30f), now = debounce, roll = 100f)),
        )
    }

    @Test
    fun `roll guidance takes priority over food pitch guidance`() {
        val policy = GuidancePolicy()
        policy.onJudgment(
            GuidanceStateMapper.from(result(x = 0f)), result(x = 0f), 0,
            pitchDeviationDeg = 30f, phoneRollDeg = -12f,
        )
        val actions = policy.onJudgment(
            GuidanceStateMapper.from(result(x = 0f)), result(x = 0f), debounce,
            pitchDeviationDeg = 30f, phoneRollDeg = -12f,
        )
        assertEquals(listOf(GuidanceDirection.ROLL_TURN_LEFT.utterance), speech(actions))
    }

    @Test
    fun `null roll keeps existing behaviour untouched`() {
        val actions = GuidancePolicy().feed(result(x = -0.25f), now = 0, roll = null)
        assertEquals(listOf(GuidanceDirection.Clock(11).utterance), speech(actions))
    }
}
