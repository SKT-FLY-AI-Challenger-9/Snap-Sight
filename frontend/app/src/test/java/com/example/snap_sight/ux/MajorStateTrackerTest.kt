package com.example.snap_sight.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MajorStateTracker] — READY/LOST "새로 진입" 판정만 검증한다 (Android 의존성 없는 순수 로직).
 * 반복되는 동일 상태에서 TTS가 매 프레임 재생되면 안 된다는 것이 이 클래스의 핵심 계약이다.
 */
class MajorStateTrackerTest {

    private val lost = GuidanceState(detected = false, horizontal = null, distance = null)
    private val ready = GuidanceState(
        detected = true, horizontal = HorizontalAlignment.CENTERED, distance = DistanceAlignment.CENTERED
    )
    private val offCenter = GuidanceState(
        detected = true, horizontal = HorizontalAlignment.LEFT, distance = DistanceAlignment.CENTERED
    )

    @Test
    fun firstLostFramesFiresOnce() {
        val tracker = MajorStateTracker()
        assertEquals(MajorState.LOST, tracker.onNewState(lost))
        assertNull(tracker.onNewState(lost))
        assertNull(tracker.onNewState(lost))
    }

    @Test
    fun firstReadyFramesFiresOnce() {
        val tracker = MajorStateTracker()
        assertEquals(MajorState.READY, tracker.onNewState(ready))
        assertNull(tracker.onNewState(ready))
    }

    @Test
    fun lostToReadyTransitionFiresReady() {
        val tracker = MajorStateTracker()
        tracker.onNewState(lost)
        assertEquals(MajorState.READY, tracker.onNewState(ready))
    }

    @Test
    fun readyToLostTransitionFiresLost() {
        val tracker = MajorStateTracker()
        tracker.onNewState(ready)
        assertEquals(MajorState.LOST, tracker.onNewState(lost))
    }

    @Test
    fun offCenterIsNeitherMajorStateAndDoesNotFire() {
        val tracker = MajorStateTracker()
        assertNull(tracker.onNewState(offCenter))
        assertNull(tracker.onNewState(offCenter))
    }

    @Test
    fun readyThenOffCenterThenBackToReadyFiresReadyAgain() {
        val tracker = MajorStateTracker()
        assertEquals(MajorState.READY, tracker.onNewState(ready))
        assertNull(tracker.onNewState(offCenter)) // READY를 벗어남 — 아직 LOST는 아님
        assertEquals(MajorState.READY, tracker.onNewState(ready)) // 다시 진입 — 재안내
    }

    @Test
    fun offCenterThenLostFiresLost() {
        val tracker = MajorStateTracker()
        tracker.onNewState(offCenter)
        assertEquals(MajorState.LOST, tracker.onNewState(lost))
    }
}
