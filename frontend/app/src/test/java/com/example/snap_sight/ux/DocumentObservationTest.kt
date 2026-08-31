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
