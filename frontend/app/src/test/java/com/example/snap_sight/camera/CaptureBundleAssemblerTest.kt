package com.example.snap_sight.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureBundleAssemblerTest {

    @Test
    fun `session ids are collision-resistant UUIDs`() {
        val first = newCaptureSessionId()
        val second = newCaptureSessionId()

        assertNotEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")))
    }

    @Test
    fun `late parts from cancelled generation cannot enter new bundle`() {
        val old = CaptureSessionToken("s_old", 1L)
        val current = CaptureSessionToken("s_current", 2L)
        val assembler = CaptureBundleAssembler<String, List<Int>>()
        assembler.begin(old)
        assembler.cancel()
        assembler.begin(current)

        assertNull(assembler.putRepresentative(old, "old-photo"))
        assertNull(assembler.putCandidates(old, listOf(1)))
        assertNull(assembler.putCandidates(current, listOf(2, 3)))
        assertEquals(
            AssembledCapture("new-photo", listOf(2, 3)),
            assembler.putRepresentative(current, "new-photo"),
        )
    }

    @Test
    fun `bundle emits once regardless of callback order`() {
        val token = CaptureSessionToken("s_test", 9L)
        val assembler = CaptureBundleAssembler<String, List<Int>>()
        assembler.begin(token)

        assertNull(assembler.putRepresentative(token, "photo"))
        assertEquals(
            AssembledCapture("photo", listOf(4, 5)),
            assembler.putCandidates(token, listOf(4, 5)),
        )
        assertNull(assembler.putCandidates(token, listOf(6)))
        assertNull(assembler.putRepresentative(token, "duplicate"))
    }
}
