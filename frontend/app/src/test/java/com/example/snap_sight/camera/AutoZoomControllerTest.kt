package com.example.snap_sight.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 자동 줌은 "줌인만, 한 번에 최대 1.5배, 상한 3배, 목표 = 프레이밍 목표" 규칙만 검증한다. */
class AutoZoomControllerTest {

    @Test
    fun `base zoom return can resolve a small target even when optional zoom-in is disabled`() {
        assertTrue(AutoZoomController.canResolveSmallTarget(0.6f, deviceMax = 10f))
        assertFalse(AutoZoomController.canResolveSmallTarget(1.0f, deviceMax = 10f))
        assertTrue(
            AutoZoomController.canResolveSmallTarget(
                currentZoom = 1.0f,
                deviceMax = 3.0f,
                zoomInEnabled = true,
            )
        )
        assertFalse(
            AutoZoomController.canResolveSmallTarget(
                currentZoom = 3.0f,
                deviceMax = 3.0f,
                zoomInEnabled = true,
            )
        )
    }

    @Test
    fun zoomsInTowardsTheFramingTargetButAtMostOneStep() {
        // 1.0배, 면적 0.01 → 목표 0.12 는 √12 ≈ 3.46배가 필요하지만 한 번에 1.5배까지만
        assertEquals(1.5f, AutoZoomController.nextZoom(1.0f, 0.01f, 0.12f, deviceMax = 10f)!!, 1e-4f)
        // CLOSEUP 목표 0.30, 면적 0.15 : √2 ≈ 1.414 < 1.5 → 정확히 목표 배율
        assertEquals(1.4142f, AutoZoomController.nextZoom(1.0f, 0.15f, 0.30f, deviceMax = 10f)!!, 1e-3f)
    }

    @Test
    fun zoomIsCappedAtThreeTimesOrDeviceMax() {
        assertEquals(3.0f, AutoZoomController.nextZoom(2.5f, 0.001f, 0.12f, deviceMax = 10f)!!, 1e-4f)
        assertEquals(2.0f, AutoZoomController.nextZoom(1.6f, 0.001f, 0.12f, deviceMax = 2.0f)!!, 1e-4f)
        assertNull(AutoZoomController.nextZoom(3.0f, 0.001f, 0.12f, deviceMax = 10f))
        assertNull(AutoZoomController.nextZoom(2.0f, 0.001f, 0.12f, deviceMax = 2.0f))
    }

    @Test
    fun neverZoomsOutAndLeavesTheReadyWindowAlone() {
        // FULL_BODY 목표 0.12, 허용 −0.10 → 0.02 이상이면 줌인 안 함; 너무 커도(0.5) 줌아웃 없음
        assertFalse(AutoZoomController.needsZoomIn(0.12f, 0.12f))
        assertFalse(AutoZoomController.needsZoomIn(0.03f, 0.12f))
        assertFalse(AutoZoomController.needsZoomIn(0.50f, 0.12f))
        assertTrue(AutoZoomController.needsZoomIn(0.01f, 0.12f))
        assertNull(AutoZoomController.nextZoom(1.0f, 0.50f, 0.12f, deviceMax = 10f))
        assertNull(AutoZoomController.nextZoom(1.0f, 0f, 0.12f, deviceMax = 10f))
    }

    @Test
    fun startsWideAndStillZoomsInFromThere() {
        // 0.6배에서 면적 0.005 → 한 번에 0.9배까지, 이후 반복 호출로 계단식 접근
        assertEquals(0.9f, AutoZoomController.nextZoom(0.6f, 0.005f, 0.12f, deviceMax = 10f)!!, 1e-4f)
    }
}
