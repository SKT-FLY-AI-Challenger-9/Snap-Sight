package com.example.snap_sight.ux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureArbiterTest {

    private val arbiter = AutoCaptureArbiter()
    private val hold = AutoCaptureArbiter.AUTO_CAPTURE_HOLD_MS

    @Test
    fun `fires exactly once after detection held for the full duration`() {
        assertFalse(arbiter.onJudgment(eligible = true, detected = true, nowMs = 0L))
        assertFalse(arbiter.onJudgment(eligible = true, detected = true, nowMs = hold - 1))
        assertTrue(arbiter.onJudgment(eligible = true, detected = true, nowMs = hold))
        // 발동 후에는 검출이 계속 이어져도 reset 전까지 다시 발동하지 않는다 (세션당 1회)
        assertFalse(arbiter.onJudgment(eligible = true, detected = true, nowMs = hold + 1))
        assertFalse(arbiter.onJudgment(eligible = true, detected = true, nowMs = hold * 10))
    }

    @Test
    fun `detection flicker restarts the hold window`() {
        assertFalse(arbiter.onJudgment(eligible = true, detected = true, nowMs = 0L))
        // detector flicker — 검출이 한 판정이라도 끊기면 유지 시간이 0부터 다시 시작된다
        assertFalse(arbiter.onJudgment(eligible = true, detected = false, nowMs = hold - 100))
        assertFalse(arbiter.onJudgment(eligible = true, detected = true, nowMs = hold))
        assertFalse(arbiter.onJudgment(eligible = true, detected = true, nowMs = hold + hold - 1))
        assertTrue(arbiter.onJudgment(eligible = true, detected = true, nowMs = hold + hold))
    }

    @Test
    fun `ineligible session never fires`() {
        // 풍경·일반 촬영 모드 — 검출이 아무리 오래 이어져도 수동 촬영 그대로
        assertFalse(arbiter.onJudgment(eligible = false, detected = true, nowMs = 0L))
        assertFalse(arbiter.onJudgment(eligible = false, detected = true, nowMs = hold * 10))
    }

    @Test
    fun `losing eligibility mid hold restarts the window`() {
        // 유지 도중 스펙 세대가 바뀌는 등 eligible 이 끊기면 유지 시간도 무효
        assertFalse(arbiter.onJudgment(eligible = true, detected = true, nowMs = 0L))
        assertFalse(arbiter.onJudgment(eligible = false, detected = true, nowMs = hold / 2))
        assertFalse(arbiter.onJudgment(eligible = true, detected = true, nowMs = hold))
        assertFalse(arbiter.onJudgment(eligible = true, detected = true, nowMs = hold * 2 - 1))
        assertTrue(arbiter.onJudgment(eligible = true, detected = true, nowMs = hold * 2))
    }

    @Test
    fun `reset rearms for a new session or target generation`() {
        assertFalse(arbiter.onJudgment(eligible = true, detected = true, nowMs = 0L))
        assertTrue(arbiter.onJudgment(eligible = true, detected = true, nowMs = hold))

        arbiter.reset()

        // 새 세션 — 유지 시간도 처음부터 다시 쌓는다
        assertFalse(arbiter.onJudgment(eligible = true, detected = true, nowMs = hold + 1))
        assertTrue(arbiter.onJudgment(eligible = true, detected = true, nowMs = hold + 1 + hold))
    }
}
