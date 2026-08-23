package com.example.snap_sight.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FinalFrameClientTest {

    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00)

    @Test
    fun `accepts jpeg only when response revision matches request`() {
        val descriptor = FinalFrameClient.validateFinalFrame(
            expectedRevision = 12L,
            expectedFinalFrameId = "candidate_03",
            revisionHeader = "12",
            frameIdHeader = "candidate_03",
            contentType = "image/jpeg",
            jpeg = jpeg,
        )

        assertEquals(12L, descriptor.captureRevision)
        assertEquals("candidate_03", descriptor.finalFrameId)
    }

    @Test
    fun `rejects stale revision before caller can overwrite local photo`() {
        assertThrows(IllegalStateException::class.java) {
            FinalFrameClient.validateFinalFrame(
                expectedRevision = 13L,
                expectedFinalFrameId = "representative",
                revisionHeader = "12",
                frameIdHeader = "representative",
                contentType = "image/jpeg",
                jpeg = jpeg,
            )
        }
    }

    @Test
    fun `rejects non jpeg body`() {
        assertThrows(IllegalStateException::class.java) {
            FinalFrameClient.validateFinalFrame(
                expectedRevision = 1L,
                expectedFinalFrameId = "representative",
                revisionHeader = "1",
                frameIdHeader = "representative",
                contentType = "image/jpeg",
                jpeg = byteArrayOf(1, 2, 3),
            )
        }
    }

    @Test
    fun `rejects a different final frame id or content type`() {
        assertThrows(IllegalStateException::class.java) {
            FinalFrameClient.validateFinalFrame(
                expectedRevision = 1L,
                expectedFinalFrameId = "candidate_02",
                revisionHeader = "1",
                frameIdHeader = "candidate_01",
                contentType = "image/jpeg",
                jpeg = jpeg,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            FinalFrameClient.validateFinalFrame(
                expectedRevision = 1L,
                expectedFinalFrameId = "candidate_02",
                revisionHeader = "1",
                frameIdHeader = "candidate_02",
                contentType = "application/octet-stream",
                jpeg = jpeg,
            )
        }
    }
}
