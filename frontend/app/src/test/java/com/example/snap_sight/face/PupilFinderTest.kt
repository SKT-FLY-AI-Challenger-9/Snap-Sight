package com.example.snap_sight.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 눈 영역에서 동공(어두운 덩어리) 중심 찾기 — 합성 이미지로 검증. */
class PupilFinderTest {

    /** width×height 밝은 배경에 (cx, cy) 중심의 어두운 원을 그린 합성 눈 이미지. */
    private fun syntheticEye(
        width: Int,
        height: Int,
        pupilCx: Int,
        pupilCy: Int,
        pupilRadius: Int = 3,
    ): IntArray {
        val bright = 0xFFDDDDDD.toInt()
        val dark = 0xFF101010.toInt()
        return IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val dx = x - pupilCx
            val dy = y - pupilCy
            if (dx * dx + dy * dy <= pupilRadius * pupilRadius) dark else bright
        }
    }

    @Test
    fun findsCenteredPupil() {
        val width = 40
        val height = 16
        val pupil = PupilFinder.find(syntheticEye(width, height, 20, 8), width, height)
        assertNotNull(pupil)
        assertEquals(20f, pupil!!.centerX, 1.5f)
        assertEquals(8f, pupil.centerY, 1.5f)
    }

    @Test
    fun findsOffCenterPupilForGazeRatio() {
        val width = 40
        val height = 16
        // 동공이 안쪽(왼쪽) 끝 근처 — 가로 비율 ≈ 0.15 → EYES_AWAY 판정으로 이어진다
        val pupil = PupilFinder.find(syntheticEye(width, height, 6, 8), width, height)
        assertNotNull(pupil)
        assertEquals(6f, pupil!!.centerX, 1.5f)
        assertEquals(GazeJudge.Verdict.EYES_AWAY, GazeJudge.judge(0f, 0f, 0.9f, 0.9f, pupil.centerX / width))
    }

    @Test
    fun uniformlyBrightRegionHasNoPupil() {
        // 어두운 덩어리가 없다(반사로 하얗게 날아간 경우) — 판정 불가
        val width = 40
        val height = 16
        val allBright = IntArray(width * height) { 0xFFDDDDDD.toInt() }
        assertNull(PupilFinder.find(allBright, width, height))
    }

    @Test
    fun tinyRegionIsRejected() {
        assertNull(PupilFinder.find(IntArray(4 * 4), 4, 4))
    }
}
