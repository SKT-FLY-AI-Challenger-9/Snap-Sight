// 이 파일: 눈 영역 픽셀에서 동공(가장 어두운 덩어리)의 중심을 찾는 순수 로직 (JVM 테스트 대상).
// ML Kit 얼굴 검출에는 눈동자 좌표가 없어서, 컨투어로 받은 눈 윤곽 안을 직접 본다 —
// "얼굴은 정면인데 눈동자만 옆을 보는" 경우를 잡기 위한 것 (2026-08-21 피드백).
package com.example.snap_sight.face

object PupilFinder {

    /** 눈 영역 내부 좌표(픽셀)의 동공 중심. */
    data class Pupil(val centerX: Float, val centerY: Float)

    /**
     * ARGB 픽셀 배열에서 동공 중심을 추정한다.
     *
     * 방식: 최저 밝기 + [PUPIL_LUMA_DELTA] 이내의 어두운 픽셀들의 무게중심.
     * 동공+홍채는 눈 영역에서 압도적으로 어둡기 때문에 근사로 충분하다.
     * 다음 경우 null (판정 불가 — 호출자는 fail-open):
     *  - 최저 밝기가 [MAX_PUPIL_LUMA] 초과 (반사로 하얗게 날아감·어두운 덩어리 없음)
     *  - 어두운 픽셀이 너무 적거나, 영역의 절반을 넘음 (뚜렷한 덩어리가 아님 — 그림자·감은 눈)
     */
    fun find(pixels: IntArray, width: Int, height: Int): Pupil? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        if (width * height < MIN_REGION_PIXELS) return null

        val total = width * height
        val luminance = IntArray(total)
        var minLuma = 255
        for (index in 0 until total) {
            val pixel = pixels[index]
            val r = pixel shr 16 and 0xFF
            val g = pixel shr 8 and 0xFF
            val b = pixel and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            luminance[index] = luma
            if (luma < minLuma) minLuma = luma
        }
        if (minLuma > MAX_PUPIL_LUMA) return null

        val threshold = minOf(minLuma + PUPIL_LUMA_DELTA, MAX_PUPIL_LUMA)
        var sumX = 0L
        var sumY = 0L
        var count = 0
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                if (luminance[rowOffset + x] <= threshold) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }
        if (count < MIN_DARK_PIXELS || count > total / 2) return null
        return Pupil(centerX = sumX.toFloat() / count, centerY = sumY.toFloat() / count)
    }

    // 파라미터 (리허설에서 조정) — docs/feature-expansion-plan.md 파라미터 표 방식
    internal const val MIN_REGION_PIXELS = 64
    internal const val MIN_DARK_PIXELS = 6
    /** 최저 밝기에서 이만큼 안쪽까지를 "동공 덩어리"로 본다 (0..255 스케일). */
    internal const val PUPIL_LUMA_DELTA = 40
    /** 최저 밝기가 이보다 크면 "어두운 덩어리가 없다"고 본다 (0..255). */
    internal const val MAX_PUPIL_LUMA = 120
}
