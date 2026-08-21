// 이 파일: CvFrame(RGB888 재사용 버퍼)의 영역을 Bitmap 으로 복사하는 공용 헬퍼.
// FaceIdentifier(인물 인식)와 SelfieGazeMonitor(시선 판정)가 함께 쓴다.
package com.example.snap_sight.face

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.snap_sight.cv.CvFrame

/**
 * [CvFrame] 버퍼의 [region] 을 Bitmap 으로 복사한다 (버퍼 재사용 규약 준수 — 호출 안에서 복사).
 * 영역이 비었거나 변환에 실패하면 null.
 */
internal fun CvFrame.toBitmap(region: Rect = Rect(0, 0, width, height)): Bitmap? {
    val regionWidth = region.width()
    val regionHeight = region.height()
    if (regionWidth <= 0 || regionHeight <= 0) return null
    return try {
        val pixels = IntArray(regionWidth * regionHeight)
        var index = 0
        for (y in region.top until region.bottom) {
            var offset = offsetOf(region.left, y)
            for (x in 0 until regionWidth) {
                val r = rgb[offset].toInt() and 0xFF
                val g = rgb[offset + 1].toInt() and 0xFF
                val b = rgb[offset + 2].toInt() and 0xFF
                pixels[index++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                offset += 3
            }
        }
        Bitmap.createBitmap(pixels, regionWidth, regionHeight, Bitmap.Config.ARGB_8888)
    } catch (t: Throwable) {
        Log.w("CvFrameBitmaps", "프레임 → Bitmap 변환 실패", t)
        null
    }
}
