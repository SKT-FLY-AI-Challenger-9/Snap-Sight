package com.example.snap_sight.face

import android.graphics.Bitmap
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 사물 크롭을 비교 가능한 외형 벡터로 바꾸는 로컬 임베더.
 *
 * 얼굴 전용 MobileFaceNet을 사물에 재사용하지 않기 위한 경량 기본 구현이다. 작은 정규화
 * 이미지에서 공간별 색상, 명암, 윤곽 방향을 뽑기 때문에 같은 YOLO 클래스 안에서 색·형태가
 * 다른 개인 사물을 구분하는 용도에 적합하다. 이후 전용 TFLite image embedder를 넣더라도
 * [ObjectEmbedder] 계약과 DB의 [modelId] 분리는 그대로 유지된다.
 */
interface ObjectEmbedder {
    val isAvailable: Boolean
    val modelId: String
    fun embed(objectCrop: Bitmap): FloatArray?
    fun close() = Unit
}

class LocalObjectAppearanceEmbedder : ObjectEmbedder {
    override val isAvailable: Boolean = true
    override val modelId: String = MODEL_ID

    override fun embed(objectCrop: Bitmap): FloatArray? {
        if (objectCrop.width <= 0 || objectCrop.height <= 0) return null
        val scaled = if (objectCrop.width == SAMPLE_SIZE && objectCrop.height == SAMPLE_SIZE) {
            objectCrop
        } else {
            Bitmap.createScaledBitmap(objectCrop, SAMPLE_SIZE, SAMPLE_SIZE, true)
        }

        return try {
            val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
            scaled.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            l2Normalize(extractFeatures(pixels))
        } finally {
            if (scaled !== objectCrop) scaled.recycle()
        }
    }

    private fun extractFeatures(pixels: IntArray): FloatArray {
        val features = FloatArray(FEATURE_DIM)
        val cellCounts = IntArray(GRID_SIZE * GRID_SIZE)

        for (y in 0 until SAMPLE_SIZE) {
            for (x in 0 until SAMPLE_SIZE) {
                val pixel = pixels[y * SAMPLE_SIZE + x]
                val red = (pixel shr 16 and 0xFF) / 255f
                val green = (pixel shr 8 and 0xFF) / 255f
                val blue = (pixel and 0xFF) / 255f
                val max = maxOf(red, green, blue)
                val min = minOf(red, green, blue)
                val delta = max - min

                val cellX = x * GRID_SIZE / SAMPLE_SIZE
                val cellY = y * GRID_SIZE / SAMPLE_SIZE
                val cell = cellY * GRID_SIZE + cellX
                val colorBase = cell * 3
                features[colorBase] += red
                features[colorBase + 1] += green
                features[colorBase + 2] += blue
                cellCounts[cell]++

                val hueSector = when {
                    delta == 0f -> 0f
                    max == red -> (green - blue) / delta
                    max == green -> (blue - red) / delta + 2f
                    else -> (red - green) / delta + 4f
                }
                val hue = (((hueSector % 6f) + 6f) % 6f) / 6f
                val saturation = if (max == 0f) 0f else delta / max
                features[COLOR_DIM + (hue * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)] += saturation
                features[COLOR_DIM + HUE_BINS + (max * VALUE_BINS).toInt().coerceIn(0, VALUE_BINS - 1)]++
            }
        }

        for (cell in cellCounts.indices) {
            val count = cellCounts[cell].coerceAtLeast(1).toFloat()
            val base = cell * 3
            features[base] /= count
            features[base + 1] /= count
            features[base + 2] /= count
        }
        val pixelCount = pixels.size.toFloat()
        for (bin in 0 until HUE_BINS + VALUE_BINS) {
            features[COLOR_DIM + bin] /= pixelCount
        }

        val edgeBase = COLOR_DIM + HUE_BINS + VALUE_BINS
        var totalEdgeMagnitude = 0f
        for (y in 1 until SAMPLE_SIZE - 1) {
            for (x in 1 until SAMPLE_SIZE - 1) {
                val left = luminance(pixels[y * SAMPLE_SIZE + x - 1])
                val right = luminance(pixels[y * SAMPLE_SIZE + x + 1])
                val up = luminance(pixels[(y - 1) * SAMPLE_SIZE + x])
                val down = luminance(pixels[(y + 1) * SAMPLE_SIZE + x])
                val dx = right - left
                val dy = down - up
                val magnitude = sqrt(dx * dx + dy * dy)
                if (magnitude < MIN_EDGE_MAGNITUDE) continue
                val angle = (atan2(dy, dx) + Math.PI).toFloat() / (2f * Math.PI.toFloat())
                val bin = (angle * EDGE_BINS).toInt().coerceIn(0, EDGE_BINS - 1)
                features[edgeBase + bin] += magnitude
                totalEdgeMagnitude += magnitude
            }
        }
        if (totalEdgeMagnitude > 0f) {
            for (bin in 0 until EDGE_BINS) features[edgeBase + bin] /= totalEdgeMagnitude
        }
        return features
    }

    private fun luminance(pixel: Int): Float =
        0.299f * (pixel shr 16 and 0xFF) +
            0.587f * (pixel shr 8 and 0xFF) +
            0.114f * (pixel and 0xFF)

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var squaredNorm = 0f
        for (value in vector) squaredNorm += value * value
        if (squaredNorm <= 0f) return vector
        val inverse = 1f / sqrt(squaredNorm)
        for (index in vector.indices) vector[index] *= inverse
        return vector
    }

    private companion object {
        const val MODEL_ID = "local_appearance_v1"
        const val SAMPLE_SIZE = 32
        const val GRID_SIZE = 4
        const val HUE_BINS = 12
        const val VALUE_BINS = 4
        const val EDGE_BINS = 8
        const val COLOR_DIM = GRID_SIZE * GRID_SIZE * 3
        const val FEATURE_DIM = COLOR_DIM + HUE_BINS + VALUE_BINS + EDGE_BINS
        const val MIN_EDGE_MAGNITUDE = 8f
    }
}
