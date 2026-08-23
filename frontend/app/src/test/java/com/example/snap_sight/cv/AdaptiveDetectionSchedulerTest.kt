package com.example.snap_sight.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveDetectionSchedulerTest {
    private fun target(predicted: Boolean = false) = TrackedObject(
        trackId = 1,
        label = "person",
        confidence = 0.9f,
        bbox = BoundingBox(0.2f, 0.1f, 0.8f, 0.9f),
        classId = 0,
        predicted = predicted,
        observationAgeMs = if (predicted) 100L else 0L,
    )

    private fun selection(
        vararg targets: TrackedObject,
        state: TargetSelectionState =
            if (targets.isEmpty()) TargetSelectionState.SEARCHING else TargetSelectionState.SELECTED,
    ) = TargetSelection(
        state = state,
        candidates = targets.toList(),
    )

    @Test
    fun `search lock and lost use distinct wall clock cadences`() {
        val scheduler = AdaptiveDetectionScheduler()
        assertEquals(150L, scheduler.intervalMs(150L, nowMs = 0L))

        scheduler.onDetectorResult(selection(target()), nowMs = 0L)
        assertEquals(DetectionCadenceState.LOCKED, scheduler.state)
        assertEquals(300L, scheduler.intervalMs(150L, nowMs = 100L))

        // predicted target는 detector 관측을 새로 만든 것이 아니다.
        scheduler.onDetectorResult(selection(target(predicted = true)), nowMs = 500L)
        assertEquals(DetectionCadenceState.LOST, scheduler.state)
        assertEquals(97L, scheduler.intervalMs(150L, nowMs = 500L))
    }

    @Test
    fun `scheduler decisions depend on elapsed time not frame count`() {
        val scheduler = AdaptiveDetectionScheduler()
        assertFalse(scheduler.shouldRunDetector(149L, 0L, 150L))
        assertTrue(scheduler.shouldRunDetector(150L, 0L, 150L))
        // 몇 번 호출했는지는 상태에 영향을 주지 않는다.
        repeat(20) { assertFalse(scheduler.shouldRunDetector(149L, 0L, 150L)) }
    }

    @Test
    fun `under-count searching candidates do not falsely enter locked cadence`() {
        val scheduler = AdaptiveDetectionScheduler()
        scheduler.onDetectorResult(
            selection(target(), state = TargetSelectionState.SEARCHING),
            nowMs = 0L,
        )
        assertEquals(DetectionCadenceState.SEARCHING, scheduler.state)
        assertEquals(150L, scheduler.intervalMs(150L, nowMs = 1L))
    }

    @Test
    fun `thermal slowdown is combined with adaptive state`() {
        val scheduler = AdaptiveDetectionScheduler()
        scheduler.onDetectorResult(selection(target()), nowMs = 0L)
        assertEquals(600L, scheduler.intervalMs(150L, thermalSlowdown = 2f, nowMs = 1L))
    }

    @Test
    fun `zero base interval preserves every-frame compatibility`() {
        val scheduler = AdaptiveDetectionScheduler()
        scheduler.onDetectorResult(selection(target()), nowMs = 0L)
        assertEquals(0L, scheduler.intervalMs(0L, nowMs = 0L))
        assertTrue(scheduler.shouldRunDetector(0L, 0L, 0L))
    }
}
