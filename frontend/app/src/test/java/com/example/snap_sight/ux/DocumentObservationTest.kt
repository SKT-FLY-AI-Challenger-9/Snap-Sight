package com.example.snap_sight.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** [DocumentObservation.fromLines] — 합집합 상자, 각도 중앙값, 글자 높이 기울기 (2026-08-30). */
class DocumentObservationTest {

    private fun line(top: Float, height: Float, left: Float = 0.1f, right: Float = 0.9f, angle: Float = 0f) =
        TextLineBox(left = left, top = top, right = right, bottom = top + height, angleDegrees = angle)

    @Test
    fun `empty lines give no observation`() {
        assertNull(DocumentObservation.fromLines(emptyList(), glareFraction = 0f, nowMs = 0))
    }

    @Test
    fun `union box spans all lines and clamps to the frame`() {
        val obs = DocumentObservation.fromLines(
            listOf(line(top = 0.2f, height = 0.05f, left = 0.3f, right = 0.6f), line(top = 0.7f, height = 0.05f, left = -0.2f, right = 1.4f)),
            glareFraction = 1.5f, nowMs = 42,
        )
        assertNotNull(obs)
        obs!!
        assertEquals(0f, obs.left, 1e-6f)
        assertEquals(1f, obs.right, 1e-6f)
        assertEquals(0.2f, obs.top, 1e-6f)
        assertEquals(0.75f, obs.bottom, 1e-6f)
        assertEquals(2, obs.lineCount)
        assertEquals(1f, obs.glareFraction, 1e-6f)
        assertEquals(42L, obs.atMs)
    }

    @Test
    fun `angle is the median so one bad line does not dominate`() {
        val lines = listOf(line(0.1f, 0.05f, angle = 2f), line(0.2f, 0.05f, angle = 3f), line(0.3f, 0.05f, angle = 40f))
        assertEquals(3f, DocumentObservation.medianAngle(lines), 1e-6f)
    }

    // ---- 모서리 4점 (외곽 v2, 2026-08-31) ----

    @Test
    fun `quad corners come from line intersections and expose convergence ratios`() {
        // 좌우 변이 기울어진 사다리꼴: 왼변 x 0.25→0.19, 오른변 0.75→0.81, 위 y=0.2, 아래 y=0.8
        val quad = DocumentQuad.from(
            left = DocLine(0.27f, 0.17f),   // y=0.2 → 0.25, y=0.8 → 0.19
            top = DocLine(0.2f, 0.2f),
            right = DocLine(0.73f, 0.83f),  // y=0.2 → 0.75, y=0.8 → 0.81
            bottom = DocLine(0.8f, 0.8f),
        )
        assertNotNull(quad)
        quad!!
        assertEquals(0.25f, quad.tl.x, 1e-3f)
        assertEquals(0.75f, quad.tr.x, 1e-3f)
        assertEquals(0.81f, quad.br.x, 1e-3f)
        assertEquals(0.19f, quad.bl.x, 1e-3f)
        // 윗변 0.5, 아랫변 0.62 → 수렴비 ≈ 0.81 (위가 멀다)
        assertEquals(0.5f / 0.62f, quad.verticalConvergence, 0.02f)
        assertEquals(1f, quad.horizontalConvergence, 0.05f)
    }

    @Test
    fun `degenerate lines give no quad`() {
        // 좌우 변이 겹침 — 변 길이 미달
        assertNull(
            DocumentQuad.from(
                left = DocLine(0.5f, 0.5f), top = DocLine(0.2f, 0.2f),
                right = DocLine(0.52f, 0.52f), bottom = DocLine(0.8f, 0.8f),
            ),
        )
        // 모서리가 프레임을 크게 벗어남
        assertNull(
            DocumentQuad.from(
                left = DocLine(-1.5f, -1.5f), top = DocLine(0.2f, 0.2f),
                right = DocLine(0.8f, 0.8f), bottom = DocLine(0.8f, 0.8f),
            ),
        )
    }

    @Test
    fun `height gradient is positive when bottom lines are taller and zero with few lines`() {
        // 위쪽 줄 높이 0.03, 아래쪽 줄 높이 0.05 → (0.05−0.03)/0.04 = 0.5
        val skewed = listOf(
            line(0.10f, 0.03f), line(0.20f, 0.03f),
            line(0.50f, 0.04f),
            line(0.70f, 0.05f), line(0.85f, 0.05f), line(0.95f, 0.05f),
        )
        assertEquals(0.5f, DocumentObservation.heightGradient(skewed), 1e-4f)
        val flat = listOf(line(0.1f, 0.04f), line(0.3f, 0.04f), line(0.5f, 0.04f), line(0.7f, 0.04f))
        assertEquals(0f, DocumentObservation.heightGradient(flat), 1e-6f)
        val few = listOf(line(0.1f, 0.03f), line(0.7f, 0.06f), line(0.9f, 0.06f))
        assertEquals(0f, DocumentObservation.heightGradient(few), 1e-6f)
    }
}
