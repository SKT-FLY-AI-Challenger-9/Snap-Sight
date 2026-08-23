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
    fun `opaque subject ref requires at least one character after local prefix`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameUploader.KnownSubject(subjectRef = "local_", kind = "person", bbox = null)
        }
    }
}
