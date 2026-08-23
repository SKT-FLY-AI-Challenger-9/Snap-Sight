package com.example.snap_sight.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 메타데이터 폴링 응답 파싱(순수 로직) 테스트 — 백엔드 `CaptureMetadataResponse` 계약 기준. */
class MetadataClientTest {

    @Test
    fun parsesDoneWithFullPayload() {
        val decision = MetadataClient.parseDecision(
            """
            {"status":"done","taxonomy_version":1,"capture_revision":4,
             "final_frame_id":"representative","brief_description":"케이크가 있어요.",
             "long_description":"따뜻한 조명 아래 케이크가 있어요.",
             "labels":["food","birthday"],"custom_labels":["제주도 여행"],"people_count":2}
            """.trimIndent()
        ) as MetadataClient.Decision.Done
        assertEquals("따뜻한 조명 아래 케이크가 있어요.", decision.metadata.longDescription)
        assertEquals("케이크가 있어요.", decision.metadata.briefDescription)
        assertEquals(listOf("food", "birthday"), decision.metadata.labels)
        assertEquals(listOf("제주도 여행"), decision.metadata.customLabels)
        assertEquals(2, decision.metadata.peopleCount)
        assertEquals(1, decision.metadata.taxonomyVersion)
        assertEquals(4L, decision.metadata.captureRevision)
        assertEquals("representative", decision.metadata.finalFrameId)
    }

    @Test
    fun doneWithNullFieldsMeansGenerationFailedButLabelsStillUsable() {
        val decision = MetadataClient.parseDecision(
            """{"status":"done","taxonomy_version":1,"long_description":null,
                "labels":[],"custom_labels":[],"people_count":null}"""
        ) as MetadataClient.Decision.Done
        assertNull(decision.metadata.longDescription) // JSON null 이 "null" 문자열로 새지 않는다
        assertNull(decision.metadata.peopleCount)
        assertTrue(decision.metadata.labels.isEmpty())
    }

    @Test
    fun pendingUsesServerRetryInterval() {
        val decision = MetadataClient.parseDecision(
            """{"status":"pending","retry_after_seconds":3}"""
        ) as MetadataClient.Decision.Pending
        assertEquals(3_000L, decision.retryAfterMs)
    }

    @Test
    fun failedStatusIsTerminal() {
        val decision = MetadataClient.parseDecision("""{"status":"failed"}""")

        assertTrue(decision is MetadataClient.Decision.Failed)
    }

    @Test
    fun `unknown status is terminal instead of pending`() {
        assertTrue(
            MetadataClient.parseDecision("""{"status":"queued"}""")
                is MetadataClient.Decision.Failed
        )
        assertTrue(MetadataClient.parseDecision("{}") is MetadataClient.Decision.Failed)
    }
}
