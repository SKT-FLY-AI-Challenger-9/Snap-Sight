package com.example.snap_sight.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoZoomControllerTest {

    @Test
    fun zoomDoublesWhenAreaIsQuarterOfTarget() {
        // 면적 0.10 → 목표 0.40: 4배 면적 = 2배 줌
        assertEquals(2.0f, AutoZoomController.requiredZoom(1.0f, 0.10f), 1e-4f)
    }

    @Test
    fun zoomScalesFromCurrentRatio() {
        // 이미 1.5배 줌 상태에서 면적 0.10 → 1.5 × 2 = 3배
        assertEquals(3.0f, AutoZoomController.requiredZoom(1.5f, 0.10f), 1e-4f)
    }

    @Test
    fun nearTriggerNeedsSmallZoom() {
        // 면적 0.19 → √(0.40/0.19) ≈ 1.451배
        assertEquals(1.4510f, AutoZoomController.requiredZoom(1.0f, 0.19f), 1e-3f)
    }
}
