package com.example.snap_sight.network

import com.example.snap_sight.camera.RingFrameBuffer
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameUploaderTest {

    @Test
    fun `candidate metadata includes matching rotation and optional blur score`() {
        val frames = listOf(
            RingFrameBuffer.Frame(byteArrayOf(1), timestampMs = 10L, rotationDegrees = 90),
            RingFrameBuffer.Frame(byteArrayOf(2), timestampMs = 20L, rotationDegrees = 270),
        )

        val json = JSONArray(FrameUploader.buildCandidateScoresJson(frames, listOf(0.1f, 0.8f)))

        assertEquals(90, json.getJSONObject(0).getInt("rotation_degrees"))
        assertEquals(270, json.getJSONObject(1).getInt("rotation_degrees"))
        assertEquals(0.1, json.getJSONObject(0).getDouble("blur_score"), 0.0001)
    }

    @Test
    fun `eyes closed score is serialized per candidate and omitted when unknown`() {
        val frames = listOf(
            RingFrameBuffer.Frame(ByteArray(1), timestampMs = 1L, rotationDegrees = 0),
            RingFrameBuffer.Frame(ByteArray(1), timestampMs = 2L, rotationDegrees = 0),
        )
        val json = JSONArray(
            FrameUploader.buildCandidateScoresJson(frames, listOf(0.1f, 0.2f), listOf(0.9f, null))
        )

        assertEquals(0.9, json.getJSONObject(0).getDouble("eyes_closed_score"), 0.0001)
        assertFalse(json.getJSONObject(1).has("eyes_closed_score")) // null = 판정 불가 → 생략
    }

    @Test
    fun `known subject payload contains opaque refs and no name field`() {
        val json = JSONArray(
            FrameUploader.buildKnownSubjectsJson(
                listOf(
                    FrameUploader.KnownSubject(
                        subjectRef = "local_track_17",
                        kind = "person",
                        bbox = null,
                    )
                )
            )
        )
        val subject = json.getJSONObject(0)

        assertEquals("local_track_17", subject.getString("subject_ref"))
        assertTrue(subject.has("kind"))
        assertFalse(subject.has("name"))
    }

    @Test
    fun `intent target flag is serialized only when true`() {
        val json = JSONArray(
            FrameUploader.buildKnownSubjectsJson(
                listOf(
                    FrameUploader.KnownSubject(
                        subjectRef = "local_track_3",
                        kind = "person",
                        bbox = null,
                        isIntentTarget = true,
                    ),
                    FrameUploader.KnownSubject(
                        subjectRef = "local_track_7",
                        kind = "person",
                        bbox = null,
                    ),
                )
            )
        )

        assertTrue(json.getJSONObject(0).getBoolean("intent_target"))
        assertFalse(json.getJSONObject(1).has("intent_target"))
    }

    @Test
    fun `unnamed subject is serialized with named=false only when it lacks a local name`() {
        val json = JSONArray(
            FrameUploader.buildKnownSubjectsJson(
                listOf(
                    FrameUploader.KnownSubject(
                        subjectRef = "local_track_9",
                        kind = "object",
                        bbox = null,
                        isIntentTarget = true,
                        hasLocalName = false,
                    ),
                    FrameUploader.KnownSubject(
                        subjectRef = "local_track_3",
                        kind = "person",
                        bbox = null,
                    ),
                )
            )
        )

        assertFalse(json.getJSONObject(0).getBoolean("named"))
        assertFalse(json.getJSONObject(1).has("named"))
    }

    @Test
    fun `opaque subject ref requires at least one character after local prefix`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameUploader.KnownSubject(subjectRef = "local_", kind = "person", bbox = null)
        }
    }
}
