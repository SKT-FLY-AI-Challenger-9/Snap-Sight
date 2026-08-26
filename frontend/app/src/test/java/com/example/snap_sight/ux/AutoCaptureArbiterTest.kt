package com.example.snap_sight.ux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureArbiterTest {

    private val arbiter = AutoCaptureArbiter()
    private val hold = AutoCaptureArbiter.MIN_READY_HOLD_MS
    private val minFresh = AutoCaptureArbiter.MIN_CONSECUTIVE_READY_FRESH

    /** 300ms 간격 실관측이 이어지는 정상 시나리오. */
    private fun judge(
        nowMs: Long,
        eligible: Boolean = true,
        ready: Boolean = true,
        fresh: Boolean = true,
    ) = arbiter.onJudgment(eligible = eligible, ready = ready, fresh = fresh, nowMs = nowMs)

    @Test
    fun `fires exactly once after fresh ready judgments span the full duration`() {
        for (t in 0 until hold step 300L) assertFalse(judge(nowMs = t))
        assertTrue(judge(nowMs = hold))
        // 발동 후에는 READY가 계속 이어져도 reset 전까지 다시 발동하지 않는다 (세션당 1회)
        assertFalse(judge(nowMs = hold + 300))
        assertFalse(judge(nowMs = hold * 10))
    }

    @Test
    fun `predicted and held judgments keep continuity but add no hold time`() {
        // 욜로가 매 프레임 탐지하지 않는 것을 재현: 실관측 2번 뒤 추정 판정이 사슬로 이어짐
        assertFalse(judge(nowMs = 0L))
        assertFalse(judge(nowMs = 300L))
        for (t in 450L..hold + 300L step 150L) {
            assertFalse(judge(nowMs = t, fresh = false))
        }
        // 사슬이 끝나고 다시 실관측이 와도, 횟수(minFresh회)를 마저 채우기 전엔 발동하지 않는다
        var t = hold + 450L
        repeat(minFresh - 3) {
            assertFalse(judge(nowMs = t))
            t += 300L
        }
        // 첫 실관측(0ms)부터 스팬은 이미 hold를 넘겼고, 이 관측이 minFresh번째라 발동
        assertTrue(judge(nowMs = t))
    }

    @Test
    fun `a fresh not-ready judgment restarts the hold window`() {
        assertFalse(judge(nowMs = 0L))
        // 구도 이탈이 실관측으로 확인되면 유지 시간·스트릭이 0부터 다시 시작된다
        assertFalse(judge(nowMs = 300L, ready = false))
        for (t in 600L until 600L + hold step 300L) assertFalse(judge(nowMs = t))
        assertTrue(judge(nowMs = 600L + hold))
    }

    @Test
    fun `too few fresh observations never fires even past the hold span`() {
        // 발열 스로틀 극단 — 실관측이 띄엄띄엄이면 스팬이 지나도 횟수를 채울 때까지 대기
        var t = 0L
        repeat(minFresh - 1) {
            assertFalse(judge(nowMs = t))
            t += hold // 관측 사이를 hold 이상 벌려도 ready 가 이어지면 끊김이 아니다
        }
        assertTrue(judge(nowMs = t))
    }

    @Test
    fun `ineligible session never fires`() {
        for (t in 0..hold * 10 step 300L) {
            assertFalse(judge(nowMs = t, eligible = false))
        }
    }

    @Test
    fun `losing eligibility mid hold restarts the window`() {
        assertFalse(judge(nowMs = 0L))
        assertFalse(judge(nowMs = 300L, eligible = false))
        for (t in 600L until 600L + hold step 300L) assertFalse(judge(nowMs = t))
        assertTrue(judge(nowMs = 600L + hold))
    }

    @Test
    fun `never fires on a predicted or held judgment even with hold already satisfied`() {
        for (t in 0 until hold step 300L) assertFalse(judge(nowMs = t))
        // 조건이 다 차 있어도 발동 판정 자체는 실관측이어야 한다 — 예측 위치를 보고 찍지 않는다
        assertFalse(judge(nowMs = hold, fresh = false))
        assertTrue(judge(nowMs = hold + 300L))
    }

    @Test
    fun `reset rearms for a new session or target generation`() {
        for (t in 0 until hold step 300L) assertFalse(judge(nowMs = t))
        assertTrue(judge(nowMs = hold))

        arbiter.reset()

        val base = hold + 300L
        for (t in base until base + hold step 300L) assertFalse(judge(nowMs = t))
        assertTrue(judge(nowMs = base + hold))
    }
}
