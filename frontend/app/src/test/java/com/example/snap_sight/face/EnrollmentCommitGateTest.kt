package com.example.snap_sight.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EnrollmentCommitGateTest {
    @Test
    fun `current generation may commit`() {
        val gate = EnrollmentCommitGate()
        val token = gate.begin()
        var committed = false

        assertTrue(gate.commitIfCurrent(token) { committed = true })
        assertTrue(committed)
        assertTrue(gate.takeCompletion(token))
        assertFalse(gate.takeCompletion(token))
    }

    @Test
    fun `cancelled generation cannot commit`() {
        val gate = EnrollmentCommitGate()
        val token = gate.begin()
        assertEquals(EnrollmentCancelResult.CANCELLED, gate.cancel())
        var committed = false

        assertFalse(gate.commitIfCurrent(token) { committed = true })
        assertFalse(committed)
        assertFalse(gate.isCurrent(token))
    }

    @Test
    fun `new enrollment invalidates detached old generation`() {
        val gate = EnrollmentCommitGate()
        val old = gate.begin()
        val current = gate.begin()

        assertFalse(gate.commitIfCurrent(old) { error("old commit must not run") })
        assertTrue(gate.isCurrent(current))
    }

    @Test
    fun `cancel without active token also invalidates a detached token`() {
        val gate = EnrollmentCommitGate()
        val detached = gate.begin()

        assertEquals(EnrollmentCancelResult.CANCELLED, gate.cancel())

        assertFalse(gate.commitIfCurrent(detached) { error("detached commit must not run") })
        assertEquals(EnrollmentCancelResult.NOT_ACTIVE, gate.cancel())
    }

    @Test
    fun `cancel after persistence reports completion and suppresses callback delivery`() {
        val gate = EnrollmentCommitGate()
        val token = gate.begin()

        assertTrue(gate.commitIfCurrent(token) { Unit })
        assertEquals(EnrollmentCancelResult.COMPLETED, gate.cancel())
        assertFalse(gate.takeCompletion(token))
    }

    @Test
    fun `cancel racing an in-progress commit waits and reports completed`() {
        val gate = EnrollmentCommitGate()
        val token = gate.begin()
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val commitResult = AtomicReference<Boolean>()
        val cancelResult = AtomicReference<EnrollmentCancelResult>()

        val commitThread = Thread {
            commitResult.set(gate.commitIfCurrent(token) {
                commitEntered.countDown()
                assertTrue(releaseCommit.await(1, TimeUnit.SECONDS))
            })
        }
        commitThread.start()
        assertTrue(commitEntered.await(1, TimeUnit.SECONDS))

        val cancelThread = Thread { cancelResult.set(gate.cancel()) }
        cancelThread.start()
        releaseCommit.countDown()
        commitThread.join(1_000L)
        cancelThread.join(1_000L)

        assertFalse(commitThread.isAlive)
        assertFalse(cancelThread.isAlive)
        assertTrue(commitResult.get())
        assertEquals(EnrollmentCancelResult.COMPLETED, cancelResult.get())
        assertFalse(gate.takeCompletion(token))
    }
}
