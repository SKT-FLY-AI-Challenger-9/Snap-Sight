package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetIntentStateTest {
    @Test
    fun `atomic spec and name update invalidates an in-flight output`() {
        val state = TargetIntentState()
        val inFlight = state.current()
        val spec = targetSpec("new-session")

        val applied = state.set(spec = spec, identityName = "민수")

        assertFalse(state.isCurrent(inFlight.generation))
        assertTrue(state.isCurrent(applied.generation))
        assertEquals(spec, applied.spec)
        assertEquals("민수", applied.identityName)
    }

    @Test
    fun `local registered-name fast path has its own generation`() {
        val state = TargetIntentState()
        val beforeRecognition = state.current()

        val local = state.set(spec = null, identityName = "민수")

        assertFalse(state.isCurrent(beforeRecognition.generation))
        assertEquals("민수", local.identityName)
        assertEquals(null, local.spec)
    }

    @Test
    fun `new session invalidates equal-value intent without relying on equality`() {
        val state = TargetIntentState()
        val spec = targetSpec("same-session")
        val previous = state.set(spec, identityName = null)

        val nextSession = state.set(spec, identityName = null, forceNewGeneration = true)

        assertTrue(nextSession.generation > previous.generation)
        assertFalse(state.isCurrent(previous.generation))
    }

    private fun targetSpec(sessionId: String) = TargetSpec(
        sessionId = sessionId,
        rawText = "사람을 찍어줘",
        source = "test",
    )
}
