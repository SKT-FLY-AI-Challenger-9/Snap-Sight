package com.example.snap_sight.face

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryReloadGateTest {
    @Test
    fun `reload is fail closed until both registries succeed`() {
        val gate = RegistryReloadGate()
        val token = gate.begin()
        assertFalse(gate.isReady)

        assertTrue(gate.complete(token, success = false) { error("must not publish") })
        assertFalse(gate.isReady)
    }

    @Test
    fun `newer reload suppresses stale atomic snapshot`() {
        val gate = RegistryReloadGate()
        val stale = gate.begin()
        val current = gate.begin()
        var stalePublished = false
        var currentPublished = false

        assertFalse(gate.complete(stale, success = true) { stalePublished = true })
        assertFalse(stalePublished)
        assertTrue(gate.complete(current, success = true) { currentPublished = true })
        assertTrue(currentPublished)
        assertTrue(gate.isReady)
    }
}
