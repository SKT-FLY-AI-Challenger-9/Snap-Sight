package com.example.snap_sight.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 상반신 줌 (2026-08-27 테스트 기능) — 전신 높이(1.0x 기준) 추정으로부터 다음 배율을
 * 계산하는 [AutoZoomController.nextUpperBodyZoom] 의 순수 로직 검증.
 * 목표: 전신 bbox 높이 = 프레임 × [AutoZoomController.UPPER_BODY_HEIGHT_TARGET].
 */
class UpperBodyZoomTest {

    private val ceiling = AutoZoomController.MAX_ZOOM // 기기 max 가 더 크다고 가정

    private fun next(current: Float, fullHeight: Float) =
        AutoZoomController.nextUpperBodyZoom(current, fullHeight, ceiling)

    @Test
    fun `zooms in stepwise toward the upper-body target`() {
        // 전신이 프레임의 70% — 목표 배율 1.8/0.7 ≈ 2.57, 한 번에 1.5배까지만
        assertEquals(1.5f, next(current = 1.0f, fullHeight = 0.7f)!!, 1e-3f)
        assertEquals(2.25f, next(current = 1.5f, fullHeight = 0.7f)!!, 1e-3f)
        // 남은 거리가 스텝 한도 안이면 목표 배율에 그대로 도달
        assertEquals(1.8f / 0.7f, next(current = 2.25f, fullHeight = 0.7f)!!, 1e-3f)
    }

    @Test
    fun `deadband keeps the zoom steady near the target`() {
        // 목표(≈2.57)와 0.15 미만 차이 — 미세 진동으로 배율이 움찔거리지 않게 유지
        assertNull(next(current = 2.5f, fullHeight = 0.7f))
    }

    @Test
    fun `far subject caps at the zoom ceiling`() {
        // 전신 30% — 이론 목표 6배지만 상한(3배)까지만, 스텝 한도(1.5배)로 접근
        assertEquals(1.5f, next(current = 1.0f, fullHeight = 0.3f)!!, 1e-3f)
        assertEquals(3.0f, next(current = 2.5f, fullHeight = 0.3f)!!, 1e-3f)
    }

    @Test
    fun `subject approaching zooms back out within the step limit`() {
        // 대상이 다가와 전신 추정이 커짐(0.7→1.4) — 과확대 상태(3.0)에서 목표 1.29로 복귀,
        // 단 한 번에 1.5배 비율까지만 내려간다
        assertEquals(2.0f, next(current = 3.0f, fullHeight = 1.4f)!!, 1e-3f)
        assertEquals(1.8f / 1.4f, next(current = 1.6f, fullHeight = 1.4f)!!, 1e-3f)
        // 목표 근처(차이 < 데드밴드)에 오면 유지
        assertNull(next(current = 1.35f, fullHeight = 1.4f))
    }

    @Test
    fun `never drops below the base zoom`() {
        // 전신이 이미 프레임을 넘는 추정치라도 촬영 최소 배율(1.0) 밑으로는 안 내려간다
        assertNull(next(current = 1.0f, fullHeight = 1.9f))
    }

    @Test
    fun `invalid height estimate does nothing`() {
        assertNull(next(current = 1.0f, fullHeight = 0f))
    }
}
