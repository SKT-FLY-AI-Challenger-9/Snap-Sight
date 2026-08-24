package com.example.snap_sight.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PortraitAutoCrop.computeCrop] 순수 기하 검증 — 얼굴이 크롭의 세로 3분할선 × 상단 1/3에
 * 오는지, 여백이 부족하거나 무의미한 크롭은 null 로 포기하는지.
 */
class PortraitAutoCropTest {

    private val imageWidth = 4000
    private val imageHeight = 3000

    private fun faceAt(cx: Int, cy: Int, size: Int = 400) =
        PortraitAutoCrop.Box(cx - size / 2, cy - size / 2, size, size)

    @Test
    fun `centered face is placed on the left third line`() {
        val crop = PortraitAutoCrop.computeCrop(faceAt(2000, 1500), imageWidth, imageHeight)
        assertNotNull(crop)
        crop!!
        // 종횡비 유지
        assertEquals(
            imageWidth.toFloat() / imageHeight,
            crop.width.toFloat() / crop.height,
            0.01f,
        )
        // 얼굴 중심이 크롭의 1/3 지점(±2%)에 온다
        val faceXInCrop = (2000f - crop.left) / crop.width
        val faceYInCrop = (1500f - crop.top) / crop.height
        assertEquals(1f / 3f, faceXInCrop, 0.02f)
        assertEquals(1f / 3f, faceYInCrop, 0.02f)
        // 크롭이 이미지 안에 있다
        assertTrue(crop.left >= 0 && crop.top >= 0)
        assertTrue(crop.left + crop.width <= imageWidth)
        assertTrue(crop.top + crop.height <= imageHeight)
    }

    @Test
    fun `face on the right half targets the right third line`() {
        val crop = PortraitAutoCrop.computeCrop(faceAt(2800, 1500), imageWidth, imageHeight)
        assertNotNull(crop)
        val faceXInCrop = (2800f - crop!!.left) / crop.width
        assertEquals(2f / 3f, faceXInCrop, 0.02f)
    }

    @Test
    fun `face too close to the edge gives up instead of over-cropping`() {
        // 필요한 축소율이 MIN_SCALE 아래로 떨어지는 위치 — 원본 유지
        assertNull(PortraitAutoCrop.computeCrop(faceAt(200, 1500), imageWidth, imageHeight))
        assertNull(PortraitAutoCrop.computeCrop(faceAt(2000, 200), imageWidth, imageHeight))
    }

    @Test
    fun `face already on the third line is a no-op`() {
        // 세로 1/3(1333), 가로 상단 1/3(1000) 근처 — s≈1 이라 자를 이유가 없다
        assertNull(PortraitAutoCrop.computeCrop(faceAt(1333, 1000), imageWidth, imageHeight))
    }

    @Test
    fun `dominant face skips thirds composition`() {
        // 크롭 대비 얼굴이 너무 큰 초근접 컷 — 3분할이 성립하지 않는다
        assertNull(
            PortraitAutoCrop.computeCrop(faceAt(2000, 1500, size = 2400), imageWidth, imageHeight),
        )
    }

    @Test
    fun `degenerate image returns null`() {
        assertNull(PortraitAutoCrop.computeCrop(faceAt(1, 1, size = 1), 2, 2))
    }
}
