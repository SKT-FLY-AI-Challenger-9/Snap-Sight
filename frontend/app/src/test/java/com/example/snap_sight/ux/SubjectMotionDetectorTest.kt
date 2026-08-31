package com.example.snap_sight.ux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SubjectMotionDetector] — 줌 허용 조건인 "정지" 판정. bbox 지터는 정지로, 빠른 중심 이동·
 * 크기 변화는 움직임으로, 줌 배율 변화·카메라 회전 구간은 판정 제외로 다룬다 (2026-08-30).
 */
class SubjectMotionDetectorTest {

    private val detector = SubjectMotionDetector()

    private fun feed(
        cx: Float,
        cy: Float = 0.5f,
        scale: Float = 0.5f,
        zoom: Float = 1f,
        camera: Pair<Float, Float>? = null,
        at: Long,
    ) = detector.onObservation(
        centerX = cx, centerY = cy, scale = scale, zoomRatio = zoom,
        cameraOrientationRad = camera, nowMs = at,
    )

    @Test
    fun `no observation is neither moving nor static`() {
        assertFalse(detector.isMoving(0))
        assertFalse(detector.isStatic(0))
    }

    @Test
    fun `small jitter counts as static once the confirm window has passed`() {
        feed(cx = 0.50f, at = 0)
        assertFalse(detector.isStatic(0)) // 첫 관측 직후에는 아직 확인 전
        feed(cx = 0.52f, at = 300)
        feed(cx = 0.49f, at = 600)
        assertFalse(detector.isMoving(600))
        assertTrue(detector.isStatic(SubjectMotionDetector.STATIC_CONFIRM_MS))
    }

    @Test
    fun `fast center displacement flags moving and holds for MOVING_HOLD_MS`() {
        feed(cx = 0.30f, at = 0)
        // 300ms 에 프레임 폭의 15% 이동 = 0.5/s ≫ 임계값
        val moving = feed(cx = 0.45f, at = 300)
        assertTrue(moving)
        assertTrue(detector.isMoving(300 + SubjectMotionDetector.MOVING_HOLD_MS - 1))
        assertFalse(detector.isStatic(300 + SubjectMotionDetector.MOVING_HOLD_MS - 1))
        assertFalse(detector.isMoving(300 + SubjectMotionDetector.MOVING_HOLD_MS))
    }

    @Test
    fun `static must be re-confirmed after motion stops`() {
        feed(cx = 0.30f, at = 0)
        feed(cx = 0.45f, at = 300) // 움직임 — 1300ms 까지 유지
        // 600ms 부터 정지 관측 — 확인 창(600ms)은 1200ms 에 차지만 유지 창이 1300ms 까지라 그때 정지
        feed(cx = 0.45f, at = 600)
        feed(cx = 0.45f, at = 900)
        val holdEnd = 300 + SubjectMotionDetector.MOVING_HOLD_MS
        assertFalse(detector.isStatic(holdEnd - 1))
        assertTrue(detector.isStatic(holdEnd))
    }

    @Test
    fun `observations closer than MIN_JUDGE_DT_MS are not compared`() {
        feed(cx = 0.30f, at = 0)
        // 100ms 에 5% 이동은 0.5/s 지만 비교 간격 미만이라 판정하지 않는다
        assertFalse(feed(cx = 0.35f, at = 100))
        // 앵커(0ms, 0.30)가 유지되므로 300ms 시점에는 누적 이동(0.30→0.37 ≈ 0.23/s)으로 판정
        assertTrue(feed(cx = 0.37f, at = 300))
    }

    @Test
    fun `rapid scale change flags moving even without center displacement`() {
        feed(cx = 0.5f, scale = 0.30f, at = 0)
        // 300ms 에 크기 30% 증가 = 1.0/s ≫ 0.35/s
        assertTrue(feed(cx = 0.5f, scale = 0.39f, at = 300))
    }

    @Test
    fun `frames across a zoom change are not compared`() {
        feed(cx = 0.40f, scale = 0.30f, zoom = 1.0f, at = 0)
        // 줌 1.0→1.15: 중심 오프셋·크기가 모두 커지지만 움직임이 아니다
        assertFalse(feed(cx = 0.385f, scale = 0.345f, zoom = 1.15f, at = 300))
        assertTrue(detector.isStatic(SubjectMotionDetector.STATIC_CONFIRM_MS))
    }

    @Test
    fun `camera rotation skips the judgment and restarts static confirmation`() {
        feed(cx = 0.30f, camera = 0f to 0f, at = 0)
        // 300ms 동안 yaw 0.3rad(≈1 rad/s) 회전 — bbox 가 크게 움직여도 피사체 판정은 보류
        val at300 = 300L
        assertFalse(feed(cx = 0.55f, camera = -0.3f to 0f, at = at300))
        assertFalse(detector.isStatic(at300)) // 정지 타이머가 300ms 에서 다시 시작
        assertTrue(detector.isStatic(at300 + SubjectMotionDetector.STATIC_CONFIRM_MS))
    }

    @Test
    fun `long observation gap starts a fresh baseline`() {
        feed(cx = 0.30f, at = 0)
        val later = SubjectMotionDetector.MAX_GAP_MS + 500
        // 2초 만에 반대편으로 옮겨 있어도 궤적 단절로 보고 움직임으로 치지 않는다
        assertFalse(feed(cx = 0.80f, at = later))
        assertFalse(detector.isStatic(later))
        assertTrue(detector.isStatic(later + SubjectMotionDetector.STATIC_CONFIRM_MS))
    }

    @Test
    fun `reset clears motion and static state`() {
        feed(cx = 0.30f, at = 0)
        feed(cx = 0.45f, at = 300)
        detector.reset()
        assertFalse(detector.isMoving(300))
        assertFalse(detector.isStatic(10_000))
    }
}
