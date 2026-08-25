package com.example.snap_sight.ux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureArbiterTest {

    private val arbiter = AutoCaptureArbiter()
    private val hold = AutoCaptureArbiter.AUTO_CAPTURE_HOLD_MS
    private val minFresh = AutoCaptureArbiter.MIN_FRESH_OBSERVATIONS

    /** LOCKED cadence(300ms)로 실관측이 이어지는 정상 시나리오. */
    private fun judge(
        nowMs: Long,
        eligible: Boolean = true,
        detected: Boolean = true,
        fresh: Boolean = true,
    ) = arbiter.onJudgment(eligible = eligible, detected = detected, fresh = fresh, nowMs = nowMs)

    @Test
    fun `fires exactly once after fresh observations span the full duration`() {
        // 300ms 간격 실관측 — 1.5초 시점이면 6번째 관측이라 횟수 조건도 함께 충족된다
        for (t in 0 until hold step 300L) assertFalse(judge(nowMs = t))
        assertTrue(judge(nowMs = hold))
        // 발동 후에는 검출이 계속 이어져도 reset 전까지 다시 발동하지 않는다 (세션당 1회)
        assertFalse(judge(nowMs = hold + 300))
        assertFalse(judge(nowMs = hold * 10))
    }

    @Test
    fun `predicted and held bridges keep continuity but add no hold time`() {
        // 잠깐 스친 피사체 재현: 실관측 2번 뒤 coasting(PREDICTED)·hold(HELD)가 사슬로 이어짐.
        // 예전 구현은 이 추정 관측이 유지 시간을 채워 1.5초에 발동했다 — 이제는 미발동.
        assertFalse(judge(nowMs = 0L))
        assertFalse(judge(nowMs = 300L))
        for (t in 450L..hold + 300L step 150L) {
            assertFalse(judge(nowMs = t, fresh = false))
        }
        // 사슬이 끝나고 다시 실관측이 와도, 횟수(4회)를 마저 채우기 전엔 발동하지 않는다
        assertFalse(judge(nowMs = hold + 450L))
        // 첫 실관측(0ms)부터 스팬은 이미 1.5초를 넘겼고, 이 관측이 4번째라 발동
        assertTrue(judge(nowMs = hold + 750L))
    }

    @Test
    fun `too few fresh observations never fires even past the hold span`() {
        // 발열 스로틀 극단 — 실관측이 띄엄띄엄이면 스팬이 지나도 횟수를 채울 때까지 대기
        var t = 0L
        repeat(minFresh - 1) {
            assertFalse(judge(nowMs = t))
            t += hold // 관측 사이를 hold 이상 벌려도 detected 가 이어지면 끊김이 아니다
        }
        // (minFresh)번째 실관측에서 비로소 발동
        assertTrue(judge(nowMs = t))
    }

    @Test
    fun `detection flicker restarts the hold window`() {
        assertFalse(judge(nowMs = 0L))
        // 추정 사슬까지 끊긴 진짜 유실 — 유지 시간·관측 횟수가 0부터 다시 시작된다
        assertFalse(judge(nowMs = 300L, detected = false, fresh = false))
        for (t in 600L until 600L + hold step 300L) assertFalse(judge(nowMs = t))
        assertTrue(judge(nowMs = 600L + hold))
    }

    @Test
    fun `ineligible session never fires`() {
        // 풍경·일반 촬영 모드 — 검출이 아무리 오래 이어져도 수동 촬영 그대로
        for (t in 0..hold * 10 step 300L) {
            assertFalse(judge(nowMs = t, eligible = false))
        }
    }

    @Test
    fun `losing eligibility mid hold restarts the window`() {
        // 유지 도중 스펙 세대가 바뀌는 등 eligible 이 끊기면 유지 시간도 무효
        assertFalse(judge(nowMs = 0L))
        assertFalse(judge(nowMs = 300L, eligible = false))
        for (t in 600L until 600L + hold step 300L) assertFalse(judge(nowMs = t))
        assertTrue(judge(nowMs = 600L + hold))
    }

    @Test
    fun `never fires on a predicted or held judgment`() {
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

        // 새 세션 — 유지 시간도 처음부터 다시 쌓는다
        val base = hold + 300L
        for (t in base until base + hold step 300L) assertFalse(judge(nowMs = t))
        assertTrue(judge(nowMs = base + hold))
    }
}
