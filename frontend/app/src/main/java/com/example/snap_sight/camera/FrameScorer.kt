package com.example.snap_sight.camera

import android.graphics.BitmapFactory

/**
 * ⑦ 온디바이스 휴리스틱 스코어링 — 후보 프레임의 품질 점수.
 *
 * 현재는 블러(흐림) 의심도만 계산한다. 라플라시안 분산이 낮을수록 경계가 뭉개진
 * 프레임이라는 고전적 지표를 쓰고, 계산 비용을 줄이기 위해 축소 디코딩한다.
 * 눈감음 의심도는 얼굴 랜드마크가 필요해 후속 이슈에서 다룬다.
 *
 * 점수 의미: 0.0(선명) ~ 1.0(심하게 흐림). 백엔드 `candidate_scores` 계약과 같은 방향
 * (⑧ MLLM 이 "의심도" 로 참고 — backend/mllm/prompts.py).
 */
object FrameScorer {

    /** 후보 JPEG 1장의 블러 의심도. 디코딩 실패 시 최악(1.0)으로 취급해 MLLM 이 걸러내게 한다. */
    fun blurScore(jpeg: ByteArray): Float {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return WORST

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return WORST

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        val luma = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            // BT.601 근사 luma (0..255)
            luma[i] = 0.299f * ((p shr 16) and 0xFF) +
                0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)
        }
        return blurScoreOfLuma(luma, width, height)
    }

    /**
     * 순수 계산부 (JVM 단위 테스트 대상).
     * 3x3 라플라시안 응답의 분산을 선명도로 보고, [SHARPNESS_REF] 로 정규화해 뒤집는다.
     */
    internal fun blurScoreOfLuma(luma: FloatArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3 || luma.size < width * height) return WORST

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val lap = 4f * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - width] - luma[i + width]
                sum += lap
                sumSq += lap.toDouble() * lap
                count++
            }
        }
        val mean = sum / count
        val variance = (sumSq / count - mean * mean).toFloat()
        val sharpness = (variance / SHARPNESS_REF).coerceIn(0f, 1f)
        return 1f - sharpness
    }

    /** 계산용 축소 목표 너비. 화질 판단에는 충분하고 후보 6장 처리도 수십 ms 수준. */
    private const val TARGET_WIDTH = 160

    /**
     * "충분히 선명함" 으로 볼 라플라시안 분산 기준 — 실측 검증 전 추정치.
     * 실제 후보 프레임 점수 분포를 보고 조정한다.
     */
    private const val SHARPNESS_REF = 300f

    private const val WORST = 1f
}
