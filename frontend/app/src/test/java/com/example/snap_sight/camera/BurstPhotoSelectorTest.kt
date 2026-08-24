package com.example.snap_sight.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BurstPhotoSelector.sharpnessOf] 의 순수 채점 코어 검증 — 선명한(경계 또렷) 이미지가
 * 흐린(경계 완만) 이미지보다 항상 높은 점수를 받아야 연사 최고 컷 선택이 성립한다.
 */
class BurstPhotoSelectorTest {

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** 세로 경계가 급격한(선명) 절반-절반 이미지. */
    private fun sharpEdges(width: Int, height: Int): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            if ((x / 4) % 2 == 0) gray(0) else gray(255)
        }

    /** 같은 명암 범위를 완만하게 오가는(흐린) 이미지 — 모션 블러의 근사. */
    private fun blurredEdges(width: Int, height: Int): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            val phase = (x % 32) / 31f
            val v = (255 * (if (phase < 0.5f) phase * 2 else (1 - phase) * 2)).toInt()
            gray(v)
        }

    @Test
    fun `sharp image scores higher than blurred image`() {
        val width = 64
        val height = 64
        val sharp = BurstPhotoSelector.sharpnessOf(sharpEdges(width, height), width, height)
        val blurred = BurstPhotoSelector.sharpnessOf(blurredEdges(width, height), width, height)
        assertTrue("sharp=$sharp <= blurred=$blurred", sharp > blurred)
        assertTrue(blurred > 0.0)
    }

    @Test
    fun `uniform image scores zero`() {
        val width = 32
        val height = 32
        val flat = IntArray(width * height) { gray(128) }
        assertEquals(0.0, BurstPhotoSelector.sharpnessOf(flat, width, height), 1e-9)
    }

    @Test
    fun `degenerate tiny image scores zero instead of crashing`() {
        assertEquals(0.0, BurstPhotoSelector.sharpnessOf(IntArray(4) { gray(10) }, 2, 2), 0.0)
    }

    @Test
    fun `ranking is stable across brightness offset`() {
        // 노출이 조금 달라도(전체 밝기 오프셋) 선명한 컷이 계속 이겨야 한다 —
        // 라플라시안은 평균 성분을 제거하므로 오프셋에 불변이다.
        val width = 64
        val height = 64
        val sharpDark = sharpEdges(width, height).map { p ->
            gray(((p and 0xFF) * 0.7f).toInt())
        }.toIntArray()
        val blurredBright = blurredEdges(width, height).map { p ->
            gray((((p and 0xFF) * 0.9f) + 25).toInt().coerceAtMost(255))
        }.toIntArray()
        val sharp = BurstPhotoSelector.sharpnessOf(sharpDark, width, height)
        val blurred = BurstPhotoSelector.sharpnessOf(blurredBright, width, height)
        assertTrue("sharp=$sharp <= blurred=$blurred", sharp > blurred)
    }
}
