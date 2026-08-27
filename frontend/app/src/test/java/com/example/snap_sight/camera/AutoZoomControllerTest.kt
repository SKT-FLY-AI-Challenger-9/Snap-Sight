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

    // ---- 줌아웃 — 인물이 화면 60% 넘게 채우면(너무 가까움) (사용자 요청 2026-08-27) ----

    @Test
    fun `needs zoom out only past the 60 percent threshold`() {
        assertFalse(AutoZoomController.needsZoomOut(0.60f))
        assertTrue(AutoZoomController.needsZoomOut(0.61f))
        assertTrue(AutoZoomController.needsZoomOut(0.90f))
    }

    @Test
    fun `zooms out towards the trigger area but at most one step`() {
        // 2.0배, 면적 0.90 → 목표 0.60 은 √(0.6/0.9)≈0.816배가 필요 → 2.0×0.816≈1.633배
        assertEquals(1.633f, AutoZoomController.nextZoomOut(2.0f, 0.90f, 0.60f, deviceMin = 1.0f)!!, 1e-3f)
        // 한 번에 /1.5 까지만: 기준면적을 작게 줘서(0.10) 요구 축소폭을 크게 만들면(3.0배,
        // 면적 0.99 → 필요 배율 ≈0.95배) 그래도 3.0/1.5=2.0배까지만 줄어든다
        assertEquals(2.0f, AutoZoomController.nextZoomOut(3.0f, 0.99f, 0.10f, deviceMin = 1.0f)!!, 1e-4f)
    }

    @Test
    fun `zoom out never goes below the device minimum`() {
        // 물리적으로 필요한 배율(≈0.93)이 기기 최소(1.1)보다 낮아도 1.1에서 멈춘다
        assertEquals(
            1.1f,
            AutoZoomController.nextZoomOut(1.2f, 0.99f, 0.60f, deviceMin = 1.1f)!!,
            1e-4f,
        )
        // 이미 기기 최소 배율이면 더 못 줄인다 — 초광각 없는 기기는 사실상 여기서 멈춘다
        assertNull(AutoZoomController.nextZoomOut(1.0f, 0.99f, 0.60f, deviceMin = 1.0f))
    }

    @Test
    fun `zoom out does nothing when area is within the trigger threshold`() {
        assertNull(AutoZoomController.nextZoomOut(2.0f, 0.50f, 0.60f, deviceMin = 1.0f))
    }
}
