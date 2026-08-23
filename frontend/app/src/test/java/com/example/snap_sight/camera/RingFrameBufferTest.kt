package com.example.snap_sight.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingFrameBufferTest {

    @Test
    fun `buffer starts off so no jpeg permit is issued`() {
        val buffer = RingFrameBuffer()
        val gate = FrameSamplingGate(preIntervalMs = 333L, postIntervalMs = 200L)

        assertEquals(RingFrameBuffer.Mode.OFF, buffer.mode)
        assertFalse(buffer.isEnabled)
        assertEquals(0L, buffer.stats().encodedFrames)
        assertEquals(
            FrameSamplingGate.Result.DISABLED,
            gate.tryAcquire(RingFrameBuffer.Mode.OFF, timestampMs = 1_000L),
        )
    }

    @Test
    fun `pre and post modes use separate cadence and one in-flight permit`() {
        val gate = FrameSamplingGate(preIntervalMs = 333L, postIntervalMs = 200L)

        assertEquals(
            FrameSamplingGate.Result.ACQUIRED,
            gate.tryAcquire(RingFrameBuffer.Mode.PRE_CAPTURE, 0L),
        )
        assertEquals(
            FrameSamplingGate.Result.BUSY,
            gate.tryAcquire(RingFrameBuffer.Mode.PRE_CAPTURE, 1_000L),
        )
        gate.release()
        assertEquals(
            FrameSamplingGate.Result.TOO_SOON,
            gate.tryAcquire(RingFrameBuffer.Mode.PRE_CAPTURE, 332L),
        )
        assertEquals(
            FrameSamplingGate.Result.ACQUIRED,
            gate.tryAcquire(RingFrameBuffer.Mode.PRE_CAPTURE, 333L),
        )
        gate.release()
        assertEquals(
            FrameSamplingGate.Result.TOO_SOON,
            gate.tryAcquire(RingFrameBuffer.Mode.POST_CAPTURE, 532L),
        )
        assertEquals(
            FrameSamplingGate.Result.ACQUIRED,
            gate.tryAcquire(RingFrameBuffer.Mode.POST_CAPTURE, 533L),
        )
    }

    @Test
    fun `session reset does not allow a second concurrent encoder`() {
        val gate = FrameSamplingGate(preIntervalMs = 333L, postIntervalMs = 200L)
        assertEquals(
            FrameSamplingGate.Result.ACQUIRED,
            gate.tryAcquire(RingFrameBuffer.Mode.PRE_CAPTURE, 0L),
        )

        gate.reset()

        assertEquals(
            FrameSamplingGate.Result.BUSY,
            gate.tryAcquire(RingFrameBuffer.Mode.PRE_CAPTURE, 500L),
        )
        gate.release()
        assertEquals(
            FrameSamplingGate.Result.ACQUIRED,
            gate.tryAcquire(RingFrameBuffer.Mode.PRE_CAPTURE, 500L),
        )
    }

    @Test
    fun `subsampling is bounded and keeps temporal endpoints`() {
        val selected = evenlySubsample((0..15).toList(), limit = 6)

        assertEquals(6, selected.size)
        assertEquals(0, selected.first())
        assertEquals(15, selected.last())
        assertTrue(selected.zipWithNext().all { (a, b) -> a < b })
    }
}
