package com.example.snap_sight.ux

import com.example.snap_sight.cv.DeviationResult
import com.example.snap_sight.cv.FrameVisibility
import com.example.snap_sight.cv.ObservationFreshness
import com.example.snap_sight.cv.ReadinessBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GuidancePolicy] — "언제 무엇을 재생할지"만 검증한다 (Android 의존성 없음).
 * 2026-08-19 실사용 피드백의 핵심 계약: 방향 음성은 한 번에 하나·쿨다운, 진동은 방향 음성과 함께만,
 * LOST 는 디바운스 뒤 경고음(음성은 오래 지속될 때 1회), READY 는 안정화 뒤 1회.
 */
class GuidancePolicyTest {

    private fun result(
        x: Float,
        size: Float,
        y: Float? = 0f,
        freshness: ObservationFreshness = ObservationFreshness.FRESH,
        ageMs: Long = 0L,
        visibility: FrameVisibility? = null,
    ) = DeviationResult(
        subjectDetected = true,
        xDeviation = x,
        sizeDeviation = size,
        yDeviation = y,
        observationFreshness = freshness,
        observationAgeMs = ageMs,
        frameVisibility = visibility,
    )

    private val lostResult = DeviationResult(subjectDetected = false, xDeviation = null, sizeDeviation = null)

    private fun GuidancePolicy.feed(r: DeviationResult, now: Long) =
        onJudgment(GuidanceStateMapper.from(r), r, now)

    private fun speech(actions: List<GuidanceAction>) =
        actions.filterIsInstance<GuidanceAction.Speak>().map { it.text }

    // ---- 방향 안내 ----

    @Test
    fun `off-center speaks one direction word with a vibration`() {
        val policy = GuidancePolicy()
        val actions = policy.feed(result(x = -0.30f, size = 0f), now = 0)
        assertEquals(listOf(GuidanceAction.Speak(GuidanceDirection.LEFT.utterance), GuidanceAction.Vibrate), actions)
    }

    @Test
    fun `same direction repeats only after the repeat interval and vibrates only then`() {
        val policy = GuidancePolicy()
        policy.feed(result(x = 0.30f, size = 0f), now = 0)
        assertTrue(policy.feed(result(x = 0.30f, size = 0f), now = 500).isEmpty())
        assertTrue(policy.feed(result(x = 0.30f, size = 0f), now = 2_000).isEmpty())
        val again = policy.feed(result(x = 0.30f, size = 0f), now = GuidancePolicy.DIRECTION_REPEAT_MS)
        assertEquals(listOf(GuidanceDirection.RIGHT.utterance), speech(again))
        assertTrue(again.contains(GuidanceAction.Vibrate))
    }

    @Test
    fun `changed direction is spoken sooner but not faster than the minimum gap`() {
        val policy = GuidancePolicy()
        policy.feed(result(x = 0.30f, size = 0f), now = 0)
        assertTrue(policy.feed(result(x = 0f, size = -0.30f), now = 400).isEmpty())
        assertEquals(listOf(GuidanceDirection.CLOSER.utterance), speech(policy.feed(result(x = 0f, size = -0.30f), now = GuidancePolicy.DIRECTION_MIN_GAP_MS)))
    }

    @Test
    fun `the axis furthest past its threshold wins`() {
        // x: 0.25/0.20 = 1.25, size: +0.30/0.10 = 3.0 이지만 FARTHER("뒤로")는 안내하지 않으므로 x 가 뽑힌다
        val actions = GuidancePolicy().feed(result(x = 0.25f, size = 0.30f), now = 0)
        assertEquals(listOf(GuidanceDirection.RIGHT.utterance), speech(actions))
        // y 가 있으면 후보에 들어간다: y -0.50/0.25 = 2.0 > x 1.25 → 위로
        val vertical = GuidancePolicy().feed(result(x = 0.25f, size = 0f, y = -0.50f), now = 0)
        assertEquals(listOf(GuidanceDirection.UP.utterance), speech(vertical))
        val down = GuidancePolicy().feed(result(x = 0f, size = 0f, y = 0.40f), now = 0)
        assertEquals(listOf(GuidanceDirection.DOWN.utterance), speech(down))
    }

    @Test
    fun `vertical-only deviation is spoken instead of READY`() {
        // x·size 는 CENTERED(계약상 isReady) 지만 위로 벗어남 → "촬영하세요" 대신 GuidanceDirection.UP.utterance
        val policy = GuidancePolicy()
        assertEquals(listOf(GuidanceDirection.UP.utterance), speech(policy.feed(result(x = 0f, size = 0f, y = -0.30f), now = 0)))
        assertTrue(policy.feed(result(x = 0f, size = 0f, y = -0.30f), now = 300).isEmpty())
    }

    @Test
    fun `vertical within tolerance participates in READY`() {
        // x·size·y 모두 허용 범위 → READY
        val policy = GuidancePolicy()
        policy.feed(result(x = 0f, size = 0f, y = 0.10f), now = 0)
        val actions = policy.feed(result(x = 0f, size = 0f, y = 0.10f), now = GuidancePolicy.READY_DEBOUNCE_MS)
        assertEquals(listOf(GuidancePolicy.READY_UTTERANCE), speech(actions))
    }

    @Test
    fun `too small is left to auto zoom while zoom has headroom`() {
        val policy = GuidancePolicy()
        val r = result(x = 0f, size = -0.30f)
        // 줌 여유 있음 → GuidanceDirection.CLOSER.utterance는 말하지 않는다 (READY 도 아님). 다만 무한 침묵 대신
        // 4초 뒤 하트비트로 "자동으로 맞추는 중"임을 알린다 (2026-08-23 죽은 공백 방지)
        assertTrue(policy.onJudgment(GuidanceStateMapper.from(r), r, 0, zoomHandlesDistance = true).isEmpty())
        assertEquals(
            listOf(GuidancePolicy.AUTO_ZOOM_HEARTBEAT),
            speech(policy.onJudgment(GuidanceStateMapper.from(r), r, 5_000, zoomHandlesDistance = true)),
        )
        // 줌 한계 → 그때 GuidanceDirection.CLOSER.utterance
        assertEquals(listOf(GuidanceDirection.CLOSER.utterance), speech(policy.onJudgment(GuidanceStateMapper.from(r), r, 6_000, zoomHandlesDistance = false)))
        // 다른 축이 벗어나 있으면 그 축은 여전히 말한다
        val r2 = result(x = -0.30f, size = -0.30f)
        assertEquals(listOf(GuidanceDirection.LEFT.utterance), speech(GuidancePolicy().onJudgment(GuidanceStateMapper.from(r2), r2, 0, zoomHandlesDistance = true)))
        // 너무 큰 것(FARTHER)은 "뒤로"를 말하지 않고, READY 도 막지 않는다 (2026-08-23) — 안정화 대기만
        val r3 = result(x = 0f, size = 0.30f)
        assertTrue(GuidancePolicy().onJudgment(GuidanceStateMapper.from(r3), r3, 0, zoomHandlesDistance = true).isEmpty())
    }

    @Test
    fun `ready is kept with hysteresis until deviation exceeds the exit factor`() {
        val policy = GuidancePolicy()
        policy.feed(result(0f, 0f), now = 0)
        assertEquals(listOf(GuidancePolicy.READY_UTTERANCE), speech(policy.feed(result(0f, 0f), now = 300)))
        // 0.25 는 진입 임계(0.20)는 넘지만 이탈 임계(0.30)는 안 넘음 → 여전히 READY(침묵)
        assertTrue(policy.feed(result(x = 0.25f, size = 0f), now = 600).isEmpty())
        // 0.35 → 이탈 → 방향 안내
        assertEquals(listOf(GuidanceDirection.RIGHT.utterance), speech(policy.feed(result(x = 0.35f, size = 0f), now = 2_000)))
    }

    // ---- READY ----

    @Test
    fun `ready speaks once after the debounce and not again while held`() {
        val policy = GuidancePolicy()
        assertTrue(policy.feed(result(0f, 0f), now = 0).isEmpty()) // 아직 안정화 전
        assertEquals(listOf(GuidancePolicy.READY_UTTERANCE), speech(policy.feed(result(0f, 0f), now = 300)))
        assertTrue(policy.feed(result(0f, 0f), now = 600).isEmpty())
        assertTrue(policy.feed(result(0f, 0f), now = 10_000).isEmpty())
    }

    @Test
    fun `predicted frames block the shutter without resetting a stable ready episode`() {
        val policy = GuidancePolicy()
        policy.feed(result(0f, 0f), now = 0L)
        assertEquals(
            listOf(GuidancePolicy.READY_UTTERANCE),
            speech(policy.feed(result(0f, 0f), now = 300L)),
        )
        assertTrue(policy.feed(
            result(
                0f,
                0f,
                freshness = ObservationFreshness.PREDICTED,
                ageMs = 100L,
            ),
            now = 400L,
        ).isEmpty())
        // 다음 fresh keyframe에 READY는 복원되지만 같은 에피소드 음성은 반복하지 않는다.
        assertTrue(policy.feed(result(0f, 0f), now = 600L).isEmpty())
    }

    @Test
    fun `uncertain observation never asks the user to move`() {
        val predicted = result(
            x = 0.8f,
            size = 0f,
            freshness = ObservationFreshness.PREDICTED,
            ageMs = 100L,
        )
        assertTrue(GuidancePolicy().feed(predicted, now = 2_000L).isEmpty())

        val stale = result(x = -0.8f, size = 0f, ageMs = 1_000L)
        assertTrue(GuidancePolicy().feed(stale, now = 2_000L).isEmpty())
    }

    @Test
    fun `hard uncertain exit starts a new ready speech episode after restabilization`() {
        val policy = GuidancePolicy()
        policy.feed(result(0f, 0f), now = 0L)
        assertEquals(
            listOf(GuidancePolicy.READY_UTTERANCE),
            speech(policy.feed(result(0f, 0f), now = GuidancePolicy.READY_DEBOUNCE_MS)),
        )

        val hardExitAt = GuidancePolicy.READY_RESPEAK_MS + 500L
        assertTrue(
            policy.feed(
                result(
                    x = 0.8f,
                    size = 0f,
                    freshness = ObservationFreshness.PREDICTED,
                    ageMs = 100L,
                ),
                now = hardExitAt,
            ).isEmpty(),
        )
        assertTrue(policy.feed(result(0f, 0f), now = hardExitAt + 100L).isEmpty())
        assertEquals(
            listOf(GuidancePolicy.READY_UTTERANCE),
            speech(
                policy.feed(
                    result(0f, 0f),
                    now = hardExitAt + 100L + GuidancePolicy.READY_DEBOUNCE_MS,
                ),
            ),
        )
    }

    @Test
    fun `process judgment returns the exact canonical verdict used for actions`() {
        val policy = GuidancePolicy()
        val first = policy.processJudgment(
            GuidanceStateMapper.from(result(0f, 0f)),
            result(0f, 0f),
            nowMs = 0L,
        )
        assertTrue(ReadinessBlocker.UNSTABLE in first.verdict.blockers)
        assertTrue(first.actions.isEmpty())

        val stable = policy.processJudgment(
            GuidanceStateMapper.from(result(0f, 0f)),
            result(0f, 0f),
            nowMs = GuidancePolicy.READY_DEBOUNCE_MS,
        )
        assertTrue(stable.verdict.ready)
        assertEquals(listOf(GuidancePolicy.READY_UTTERANCE), speech(stable.actions))
    }

    @Test
    fun `ready flapping does not respeak within the respeak window`() {
        val policy = GuidancePolicy()
        policy.feed(result(0f, 0f), now = 0)
        policy.feed(result(0f, 0f), now = 300) // spoken
        policy.feed(result(0.40f, 0f), now = 400) // 이탈 임계(0.30) 초과 → READY 벗어남
        policy.feed(result(0f, 0f), now = 500)
        assertTrue(policy.feed(result(0f, 0f), now = 900).isEmpty()) // 3초 안이라 반복 없음
        assertEquals(
            listOf(GuidancePolicy.READY_UTTERANCE),
            speech(policy.feed(result(0f, 0f), now = 300 + GuidancePolicy.READY_RESPEAK_MS)),
        )
    }

    // ---- LOST ----

    @Test
    fun `brief loss is silent`() {
        val policy = GuidancePolicy()
        assertTrue(policy.feed(lostResult, now = 0).isEmpty())
        assertTrue(policy.feed(lostResult, now = GuidancePolicy.LOST_DEBOUNCE_MS - 1).isEmpty())
        // 다시 찾으면 에피소드 종료 — 아무 것도 안 나갔다
        val back = policy.feed(result(0.3f, 0f), now = GuidancePolicy.LOST_DEBOUNCE_MS + 100)
        assertEquals(listOf(GuidanceDirection.RIGHT.utterance), speech(back))
    }

    @Test
    fun `sustained loss beeps at intervals and speaks only once much later`() {
        val policy = GuidancePolicy()
        policy.feed(lostResult, now = 0)
        val first = policy.feed(lostResult, now = GuidancePolicy.LOST_DEBOUNCE_MS)
        assertEquals(listOf(GuidanceAction.WarningTone), first)
        assertTrue(policy.feed(lostResult, now = GuidancePolicy.LOST_DEBOUNCE_MS + 1_000).isEmpty())
        val second = policy.feed(lostResult, now = GuidancePolicy.LOST_DEBOUNCE_MS + GuidancePolicy.LOST_TONE_INTERVAL_MS)
        assertEquals(listOf(GuidanceAction.WarningTone), second)

        val spoken = policy.feed(lostResult, now = GuidancePolicy.LOST_SPEAK_AFTER_MS)
        assertTrue(spoken.contains(GuidanceAction.Speak(GuidancePolicy.LOST_UTTERANCE)))
        // 이후 같은 에피소드에서는 음성 반복 없음 (톤만)
        val later = policy.feed(lostResult, now = GuidancePolicy.LOST_SPEAK_AFTER_MS + GuidancePolicy.LOST_TONE_INTERVAL_MS)
        assertEquals(listOf(GuidanceAction.WarningTone), later)
    }

    @Test
    fun `reset clears spoken state for a new session`() {
        val policy = GuidancePolicy()
        policy.feed(result(0f, 0f), now = 0)
        policy.feed(result(0f, 0f), now = 300)
        policy.reset()
        policy.feed(result(0f, 0f), now = 400)
        assertEquals(listOf(GuidancePolicy.READY_UTTERANCE), speech(policy.feed(result(0f, 0f), now = 700)))
    }

    // ---- READY 보류 (셀카 모드 시선 게이트, 2026-08-21) ----

    private fun GuidancePolicy.feedBlocked(r: DeviationResult, now: Long, reason: String?) =
        onJudgment(GuidanceStateMapper.from(r), r, now, readyBlockedReason = reason)

    @Test
    fun `ready with a block reason speaks the reason instead of shoot-now`() {
        val policy = GuidancePolicy()
        val actions = policy.feedBlocked(result(0f, 0f), now = 0, reason = "카메라를 봐 주세요")
        assertEquals(listOf("카메라를 봐 주세요"), speech(actions))
        // 보류 사유는 반복 간격 안에서는 다시 말하지 않는다
        assertTrue(policy.feedBlocked(result(0f, 0f), now = 1_000, reason = "카메라를 봐 주세요").isEmpty())
        val again = policy.feedBlocked(
            result(0f, 0f), now = GuidancePolicy.DIRECTION_REPEAT_MS, reason = "카메라를 봐 주세요",
        )
        assertEquals(listOf("카메라를 봐 주세요"), speech(again))
    }

    @Test
    fun `ready fires normally once the block reason clears`() {
        val policy = GuidancePolicy()
        policy.feedBlocked(result(0f, 0f), now = 0, reason = "카메라를 봐 주세요")
        // 시선이 돌아옴 — READY 디바운스(에피소드 시작 기준)를 지나 "지금 촬영하세요"
        val actions = policy.feedBlocked(
            result(0f, 0f), now = GuidancePolicy.READY_DEBOUNCE_MS + 100, reason = null,
        )
        assertEquals(listOf(GuidancePolicy.READY_UTTERANCE), speech(actions))
    }

    @Test
    fun `block reason does not affect sessions without one`() {
        val policy = GuidancePolicy()
        policy.feed(result(0f, 0f), now = 0)
        assertEquals(
            listOf(GuidancePolicy.READY_UTTERANCE),
            speech(policy.feed(result(0f, 0f), now = GuidancePolicy.READY_DEBOUNCE_MS + 100)),
        )
    }

    // ---- 하트비트 — 방향 단어가 없는 상태에서 죽은 공백을 없앤다 (2026-08-23) ----

    @Test
    fun `oversized subject no longer blocks READY`() {
        // 크기 초과(FARTHER)는 READY 를 막지 않는다 (2026-08-23) — 그대로 찍게 두고 후처리로 넘긴다
        val policy = GuidancePolicy()
        policy.feed(result(x = 0f, size = 0.5f), now = 0)
        assertEquals(
            listOf(GuidancePolicy.READY_UTTERANCE),
            speech(policy.feed(result(x = 0f, size = 0.5f), now = GuidancePolicy.READY_DEBOUNCE_MS + 100)),
        )
    }

    @Test
    fun `visibility-blocked state speaks a heartbeat instead of staying silent`() {
        val policy = GuidancePolicy()
        // 머리가 잘린 전신 구도 — x/y/size 는 다 맞는데 VISIBILITY 만 막힌 상태
        val cropped = result(
            x = 0f, size = 0f,
            visibility = FrameVisibility(
                leftMargin = 0.4f, topMargin = 0f, rightMargin = 0.4f, bottomMargin = 0.1f,
            ),
        )
        assertTrue(policy.feed(cropped, now = 0).isEmpty())
        val actions = policy.feed(cropped, now = GuidancePolicy.HEARTBEAT_AFTER_MS)
        assertEquals(listOf(GuidancePolicy.VISIBILITY_HEARTBEAT), speech(actions))
    }

    @Test
    fun `heartbeat waits for both state persistence and speech silence`() {
        val policy = GuidancePolicy()
        policy.feed(result(x = 0.3f, size = 0f), now = 0) // GuidanceDirection.RIGHT.utterance
        val cropped = result(
            x = 0f, size = 0f,
            visibility = FrameVisibility(
                leftMargin = 0.4f, topMargin = 0f, rightMargin = 0.4f, bottomMargin = 0.1f,
            ),
        )
        // t=1s 부터 잘림 상태 — 상태 지속 4s 와 음성 공백 4s 를 모두 채워야 말한다
        assertTrue(policy.feed(cropped, now = 1_000).isEmpty())
        assertTrue(policy.feed(cropped, now = 4_500).isEmpty())
        assertEquals(
            listOf(GuidancePolicy.VISIBILITY_HEARTBEAT),
            speech(policy.feed(cropped, now = 5_000)),
        )
    }
}
