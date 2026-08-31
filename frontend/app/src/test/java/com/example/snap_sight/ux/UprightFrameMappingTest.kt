package com.example.snap_sight.ux

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [UprightFrameMapping.toDisplayPoint] — 정방향 분석 프레임 → 세로 미리보기 프레임 (2026-08-31 가로모드).
 * 회전 상수는 android.view.Surface.ROTATION_* 값(0..3)과 같다.
 */
class UprightFrameMappingTest {

    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 1e-6f)
        assertEquals(expected.second, actual.second, 1e-6f)
    }

    @Test
    fun `portrait passes through unchanged`() {
        assertPoint(0.2f to 0.7f, UprightFrameMapping.toDisplayPoint(0.2f, 0.7f, surfaceRotation = 0))
    }

    @Test
    fun `rotation90 maps user-top to portrait-right`() {
        // 기기 상단이 왼쪽(ROTATION_90): 사용자 기준 프레임 위쪽 가운데(0.5, 0)는
        // 세로 프레임의 오른쪽 가운데(1, 0.5)에 보인다
        assertPoint(1f to 0.5f, UprightFrameMapping.toDisplayPoint(0.5f, 0f, surfaceRotation = 1))
        // 사용자 기준 왼쪽 가운데(0, 0.5) → 세로 프레임 위쪽 가운데(0.5, 0)
        assertPoint(0.5f to 0f, UprightFrameMapping.toDisplayPoint(0f, 0.5f, surfaceRotation = 1))
    }

    @Test
    fun `rotation270 maps user-top to portrait-left`() {
        assertPoint(0f to 0.5f, UprightFrameMapping.toDisplayPoint(0.5f, 0f, surfaceRotation = 3))
        assertPoint(0.5f to 1f, UprightFrameMapping.toDisplayPoint(0f, 0.5f, surfaceRotation = 3))
    }

    @Test
    fun `rotation180 is point symmetric`() {
        assertPoint(0.8f to 0.3f, UprightFrameMapping.toDisplayPoint(0.2f, 0.7f, surfaceRotation = 2))
    }

    @Test
    fun `rotations are inverses of each other on corners`() {
        // 90 과 270 은 서로 역회전 — 한쪽으로 보낸 점을 반대쪽으로 보내면 제자리
        for ((x, y) in listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f, 0.25f to 0.6f)) {
            val (px, py) = UprightFrameMapping.toDisplayPoint(x, y, surfaceRotation = 1)
            // ROTATION_270 매핑은 ROTATION_90 의 역함수여야 한다
            assertPoint(x to y, UprightFrameMapping.toDisplayPoint(px, py, surfaceRotation = 3))
        }
    }

    @Test
    fun `mirroring flips display x after rotation`() {
        // 셀카 미리보기 반전은 화면(세로 프레임) 기준 — 회전을 먼저 적용한 뒤 x 를 뒤집는다
        assertPoint(0f to 0.5f, UprightFrameMapping.toDisplayPoint(0.5f, 0f, surfaceRotation = 1, mirrored = true))
    }
}
