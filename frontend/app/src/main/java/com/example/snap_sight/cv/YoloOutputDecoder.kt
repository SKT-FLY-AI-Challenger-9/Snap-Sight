package com.example.snap_sight.cv

/**
 * YOLO 출력 텐서 → [Detection] 디코딩. 런타임(TFLite/ONNX/…)에 의존하지 않는 순수 로직이다.
 *
 * 분리한 이유: letterbox 역변환과 좌표 단위 판별은 **틀려도 예외가 안 나고 조용히 틀린다**.
 * 실기기·모델 없이 JVM 단위 테스트로 고정할 수 있어야 한다.
 */
enum class YoloOutputLayout {
    /** `[1, 4 + numClasses, anchors]` — Ultralytics 기본 export */
    CHANNELS_FIRST,

    /** `[1, anchors, 4 + numClasses]` */
    ANCHORS_FIRST,

    /** `[1, detections, 6]` — xyxy + confidence + classId (NMS-free end-to-end head) */
    END_TO_END,
}

/**
 * 종횡비를 유지한 채 모델 입력 크기에 맞추고 남는 영역을 채운 결과.
 * 모델이 보는 좌표를 원본 프레임 좌표로 되돌리려면 이 값들이 필요하다.
 */
data class LetterboxGeometry(
    val inputWidth: Int,
    val inputHeight: Int,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val padX: Int,
    val padY: Int,
) {
    companion object {
        fun of(
            frameWidth: Int,
            frameHeight: Int,
            inputWidth: Int,
            inputHeight: Int,
        ): LetterboxGeometry {
            require(frameWidth > 0 && frameHeight > 0) { "frame size must be positive" }
            require(inputWidth > 0 && inputHeight > 0) { "input size must be positive" }
            val scale = minOf(
                inputWidth.toFloat() / frameWidth,
                inputHeight.toFloat() / frameHeight,
            )
            val scaledWidth = Math.round(frameWidth * scale).coerceIn(1, inputWidth)
            val scaledHeight = Math.round(frameHeight * scale).coerceIn(1, inputHeight)
            return LetterboxGeometry(
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                scaledWidth = scaledWidth,
                scaledHeight = scaledHeight,
                padX = (inputWidth - scaledWidth) / 2,
                padY = (inputHeight - scaledHeight) / 2,
            )
        }
    }
}

class YoloOutputDecoder(
    private val labels: List<String>,
    private val layout: YoloOutputLayout,
    private val anchorCount: Int,
    private val channelCount: Int,
    private val minimumConfidence: Float,
    private val nmsIouThreshold: Float,
    private val maxDetections: Int,
    applyNms: Boolean,
) {
    /**
     * 박스 좌표가 정규화([0,1])인지 입력 픽셀 단위인지. export 방식마다 달라서
     * 검출이 있는 첫 결과를 한 번 훑어 확정한 뒤 캐시한다. 아직 판별 못 했으면 null.
     */
    var coordinatesArePixels: Boolean? = null
        private set

    /**
     * end-to-end head 는 이미 중복을 제거하고 나온다. 여기서 NMS 를 또 돌리면
     * 겹쳐 선 같은 클래스 객체(예: 나란한 사람 둘)를 정당한 검출인데도 지운다.
     */
    private val suppressDuplicates = applyNms && layout != YoloOutputLayout.END_TO_END

    /**
     * @param values `[anchorCount x channelCount]` 를 평탄화한 dequantize 완료 출력
     * @return 원본 프레임 기준 normalized bbox 를 가진 검출들. 판별 불가 프레임이면 빈 리스트.
     */
    fun decode(values: FloatArray, geometry: LetterboxGeometry): List<Detection> {
        require(values.size >= anchorCount * channelCount) {
            "output buffer too small: ${values.size} < ${anchorCount * channelCount}"
        }

        // 검출이 하나도 없는 프레임으로는 좌표 단위를 판별할 수 없다 (고정 슬롯이 0 으로 채워짐).
        // 잘못 캐시하면 이후 모든 bbox 가 조용히 틀어지므로 판별을 미루고 빈 결과를 낸다.
        val usePixels = coordinatesArePixels
            ?: resolveCoordinateUnits(values)?.also { coordinatesArePixels = it }
            ?: return emptyList()

        val scaleX = if (usePixels) 1f else geometry.inputWidth.toFloat()
        val scaleY = if (usePixels) 1f else geometry.inputHeight.toFloat()

        val candidates = ArrayList<Detection>(64)
        for (anchor in 0 until anchorCount) {
            val classId: Int
            val confidence: Float
            val left: Float
            val top: Float
            val right: Float
            val bottom: Float

            if (layout == YoloOutputLayout.END_TO_END) {
                confidence = values[indexOf(anchor, 4)]
                if (confidence < minimumConfidence) continue
                classId = values[indexOf(anchor, 5)].toInt()
                left = values[indexOf(anchor, 0)] * scaleX
                top = values[indexOf(anchor, 1)] * scaleY
                right = values[indexOf(anchor, 2)] * scaleX
                bottom = values[indexOf(anchor, 3)] * scaleY
            } else {
                var bestClass = -1
                var bestScore = 0f
                for (classIndex in labels.indices) {
                    val score = values[indexOf(anchor, 4 + classIndex)]
                    if (score > bestScore) {
                        bestScore = score
                        bestClass = classIndex
                    }
                }
                if (bestClass < 0 || bestScore < minimumConfidence) continue
                classId = bestClass
                confidence = bestScore
                val centerX = values[indexOf(anchor, 0)] * scaleX
                val centerY = values[indexOf(anchor, 1)] * scaleY
                val width = values[indexOf(anchor, 2)] * scaleX
                val height = values[indexOf(anchor, 3)] * scaleY
                left = centerX - width / 2f
                top = centerY - height / 2f
                right = centerX + width / 2f
                bottom = centerY + height / 2f
            }

            if (classId !in labels.indices) continue

            // letterbox 되돌리기: 입력 텐서 픽셀 → 원본 프레임 normalized
            val bbox = BoundingBox.clipped(
                xMin = (left - geometry.padX) / geometry.scaledWidth,
                yMin = (top - geometry.padY) / geometry.scaledHeight,
                xMax = (right - geometry.padX) / geometry.scaledWidth,
                yMax = (bottom - geometry.padY) / geometry.scaledHeight,
            ) ?: continue

            candidates.add(
                Detection(
                    label = labels[classId],
                    confidence = confidence.coerceIn(0f, 1f),
                    bbox = bbox,
                    classId = classId,
                )
            )
        }

        candidates.sortByDescending { it.confidence }
        val bounded = if (candidates.size > MAX_NMS_CANDIDATES) {
            candidates.subList(0, MAX_NMS_CANDIDATES)
        } else {
            candidates
        }
        val kept = if (suppressDuplicates) nonMaximumSuppression(bounded) else bounded
        return if (kept.size > maxDetections) kept.subList(0, maxDetections) else kept
    }

    private fun indexOf(anchor: Int, channel: Int): Int = when (layout) {
        YoloOutputLayout.CHANNELS_FIRST -> channel * anchorCount + anchor
        YoloOutputLayout.ANCHORS_FIRST, YoloOutputLayout.END_TO_END ->
            anchor * channelCount + channel
    }

    /** 실제 검출된 행의 박스 값으로 좌표 단위를 판별한다. 판별 가능한 행이 없으면 null. */
    private fun resolveCoordinateUnits(values: FloatArray): Boolean? {
        var maximum = 0f
        var sawDetection = false
        for (anchor in 0 until anchorCount) {
            if (confidenceAt(values, anchor) < minimumConfidence) continue
            sawDetection = true
            for (channel in 0 until 4) {
                val value = kotlin.math.abs(values[indexOf(anchor, channel)])
                if (value > maximum) maximum = value
            }
        }
        return if (sawDetection) maximum > PIXEL_UNIT_THRESHOLD else null
    }

    /** layout 과 무관하게 이 행의 confidence 를 돌려준다. */
    private fun confidenceAt(values: FloatArray, anchor: Int): Float {
        if (layout == YoloOutputLayout.END_TO_END) return values[indexOf(anchor, 4)]
        var best = 0f
        for (classIndex in labels.indices) {
            val score = values[indexOf(anchor, 4 + classIndex)]
            if (score > best) best = score
        }
        return best
    }

    /** class 별 greedy NMS. 입력은 confidence 내림차순이어야 한다. */
    private fun nonMaximumSuppression(candidates: List<Detection>): List<Detection> {
        val kept = ArrayList<Detection>(minOf(candidates.size, maxDetections))
        val suppressed = BooleanArray(candidates.size)
        for (index in candidates.indices) {
            if (suppressed[index]) continue
            val candidate = candidates[index]
            kept.add(candidate)
            if (kept.size >= maxDetections) break
            for (other in index + 1 until candidates.size) {
                if (suppressed[other]) continue
                val challenger = candidates[other]
                if (challenger.classId != candidate.classId) continue
                if (candidate.bbox.iou(challenger.bbox) > nmsIouThreshold) {
                    suppressed[other] = true
                }
            }
        }
        return kept
    }

    companion object {
        private const val MAX_NMS_CANDIDATES = 1000

        /** 정규화 좌표는 보통 1 근처가 상한이라 여유를 둔 값. */
        private const val PIXEL_UNIT_THRESHOLD = 2f

        /**
         * 출력 텐서 형태로 layout 을 판별한다.
         * @return 판별한 layout, 해석 불가하면 null
         */
        fun layoutFor(outputShape: IntArray, classCount: Int): YoloOutputLayout? {
            if (outputShape.size != 3 || outputShape[0] != 1) return null
            val expectedChannels = 4 + classCount
            return when {
                outputShape[2] == expectedChannels -> YoloOutputLayout.ANCHORS_FIRST
                outputShape[1] == expectedChannels -> YoloOutputLayout.CHANNELS_FIRST
                outputShape[2] == END_TO_END_CHANNELS -> YoloOutputLayout.END_TO_END
                else -> null
            }
        }

        const val END_TO_END_CHANNELS = 6
    }
}
