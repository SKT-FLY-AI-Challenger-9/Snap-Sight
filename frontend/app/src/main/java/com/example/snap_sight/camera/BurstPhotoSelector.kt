package com.example.snap_sight.camera

import android.graphics.BitmapFactory
import java.io.File

/**
 * 자동촬영 연사에서 "제일 좋은 한 장"을 고르는 선명도 채점기 (2026-08-24).
 *
 * 같은 장면을 짧은 간격으로 찍은 연사끼리는 구도 차이가 작아, 실질 품질 차이는
 * 흔들림(모션 블러)과 초점에서 갈린다 — 라플라시안 응답의 분산으로 채점한다.
 * 흔들리거나 초점이 나간 컷은 경계가 뭉개져 분산이 뚝 떨어진다.
 *
 * 채점 코어([sharpnessOf])는 android.* 의존이 없어 JVM 단위 테스트 가능.
 * JPEG 디코드([scoreJpeg])만 android 에 기댄다.
 */
object BurstPhotoSelector {

    /** 채점용 다운스케일 상한 — 긴 변이 이 이하가 되게 inSampleSize 를 고른다. */
    const val SCORE_MAX_SIDE = 720

    /** JPEG 파일을 채점한다. 디코드 실패 시 최저점 — 그 컷은 사실상 탈락한다. */
    fun scoreJpeg(file: File): Double {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Double.NEGATIVE_INFINITY
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SCORE_MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return Double.NEGATIVE_INFINITY
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            sharpnessOf(pixels, width, height)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * ARGB 픽셀 배열의 라플라시안 분산 — 클수록 경계가 또렷(= 선명)하다.
     * 3x3 미만이거나 완전히 균일한 이미지는 0.
     */
    fun sharpnessOf(pixels: IntArray, width: Int, height: Int): Double {
        require(pixels.size >= width * height) { "pixel buffer smaller than width*height" }
        if (width < 3 || height < 3) return 0.0
        val luma = FloatArray(width * height)
        for (i in 0 until width * height) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        var sum = 0.0
        var sumOfSquares = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val lap = 4f * luma[i] -
                    luma[i - 1] - luma[i + 1] - luma[i - width] - luma[i + width]
                sum += lap
                sumOfSquares += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumOfSquares / count - mean * mean
    }
}
