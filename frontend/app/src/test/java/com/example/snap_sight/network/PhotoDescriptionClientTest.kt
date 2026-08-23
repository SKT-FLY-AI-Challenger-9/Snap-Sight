package com.example.snap_sight.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDescriptionClientTest {

    @Test
    fun `done retains server revision for expected-revision validation`() {
        val decision = PhotoDescriptionClient.parseDecision(
            """{"status":"done","description":"사진 설명","capture_revision":5,
                "final_frame_id":"representative"}"""
        ) as PhotoDescriptionClient.Decision.Done

        assertEquals("사진 설명", decision.description)
        assertEquals(5L, decision.captureRevision)
        assertEquals("representative", decision.finalFrameId)
    }

    @Test
    fun `failed status is terminal`() {
        assertTrue(
            PhotoDescriptionClient.parseDecision("""{"status":"failed"}""")
                is PhotoDescriptionClient.Decision.Failed
        )
    }

    @Test
    fun `unknown status is terminal instead of pending`() {
        assertTrue(
            PhotoDescriptionClient.parseDecision("""{"status":"queued"}""")
                is PhotoDescriptionClient.Decision.Failed
        )
        assertTrue(
            PhotoDescriptionClient.parseDecision("{}")
                is PhotoDescriptionClient.Decision.Failed
        )
    }
}
