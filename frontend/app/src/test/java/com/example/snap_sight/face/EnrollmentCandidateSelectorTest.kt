package com.example.snap_sight.face

import com.example.snap_sight.cv.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnrollmentCandidateSelectorTest {
    @Test
    fun `single central candidate is accepted`() {
        val boxes = listOf(box(0.30f, 0.25f, 0.70f, 0.75f))

        assertEquals(0, EnrollmentCandidateSelector.selectIndex(boxes))
    }

    @Test
    fun `single candidate too far from center is skipped`() {
        val boxes = listOf(box(0.00f, 0.00f, 0.20f, 0.20f))

        assertNull(EnrollmentCandidateSelector.selectIndex(boxes))
    }

    @Test
    fun `similarly sized candidates are ambiguous and skipped`() {
        val boxes = listOf(
            box(0.25f, 0.25f, 0.55f, 0.65f),
            box(0.48f, 0.27f, 0.77f, 0.67f),
        )

        assertNull(EnrollmentCandidateSelector.selectIndex(boxes))
    }

    @Test
    fun `central candidate with clear area dominance is accepted`() {
        val boxes = listOf(
            box(0.25f, 0.20f, 0.75f, 0.80f),
            box(0.05f, 0.10f, 0.25f, 0.40f),
        )

        assertEquals(0, EnrollmentCandidateSelector.selectIndex(boxes))
    }

    @Test
    fun `dominant candidate outside center allowance is skipped`() {
        val boxes = listOf(
            box(0.00f, 0.10f, 0.35f, 0.90f),
            box(0.75f, 0.40f, 0.90f, 0.60f),
        )

        assertNull(EnrollmentCandidateSelector.selectIndex(boxes))
    }

    @Test
    fun `empty frame is skipped`() {
        assertNull(EnrollmentCandidateSelector.selectIndex(emptyList()))
    }

    private fun box(xMin: Float, yMin: Float, xMax: Float, yMax: Float) =
        BoundingBox(xMin, yMin, xMax, yMax)
}
