package com.example.snap_sight.network

import com.example.snap_sight.network.CaptureResultClient.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 폴링 판정 파싱([CaptureResultClient.parseDecision])만 검증 — 실제 HTTP 는 계측 영역. */
class CaptureResultClientTest {

    @Test
    fun doneWithImprovement() {
        val d = CaptureResultClient.parseDecision(
            """{"status":"done","improved":true,"reason":"눈 감김이 적은 후보로 교체",
                "capture_revision":7,"final_frame_id":"candidate_02"}""")
        assertTrue(d is Decision.Done)
        assertTrue((d as Decision.Done).improved)
        assertEquals("눈 감김이 적은 후보로 교체", d.reason)
        assertEquals(7L, d.captureRevision)
        assertEquals("candidate_02", d.finalFrameId)
    }

    @Test
    fun doneWithoutImprovementAndNullReason() {
        val d = CaptureResultClient.parseDecision("""{"status":"done","improved":false,"reason":null}""")
        assertTrue(d is Decision.Done)
        assertTrue(!(d as Decision.Done).improved)
        assertNull(d.reason)
    }

    @Test
    fun pendingUsesServerRetryAfter() {
        val d = CaptureResultClient.parseDecision("""{"status":"pending","retry_after_seconds":3.5}""")
        assertTrue(d is Decision.Pending)
        assertEquals(3500L, (d as Decision.Pending).retryAfterMs)
    }

    @Test
    fun pendingWithoutRetryAfterFallsBackToDefault() {
        val d = CaptureResultClient.parseDecision("""{"status":"pending"}""")
        assertEquals(CaptureResultClient.DEFAULT_RETRY_MS, (d as Decision.Pending).retryAfterMs)
    }

    @Test
    fun absurdlySmallRetryAfterIsClamped() {
        // 서버가 0이나 음수를 줘도 폴링 폭주하지 않게 최소 0.5초
        val d = CaptureResultClient.parseDecision("""{"status":"pending","retry_after_seconds":0}""")
        assertEquals(500L, (d as Decision.Pending).retryAfterMs)
    }

    @Test
    fun failedStatusIsTerminal() {
        val d = CaptureResultClient.parseDecision(
            """{"status":"failed","reason":"capture pipeline failed"}"""
        )

        assertTrue(d is Decision.Failed)
        assertEquals("capture pipeline failed", (d as Decision.Failed).reason)
    }

    @Test
    fun `only allowlisted in-progress statuses remain pending`() {
        listOf("pending", "processing", "selecting").forEach { status ->
            assertTrue(CaptureResultClient.parseDecision("""{"status":"$status"}""") is Decision.Pending)
        }
        assertTrue(CaptureResultClient.parseDecision("""{"status":"queued"}""") is Decision.Failed)
        assertTrue(CaptureResultClient.parseDecision("{}") is Decision.Failed)
    }
}
