package com.example.snap_sight.cv

import java.util.Locale

/**
 * [② 온디바이스 CV] `ai/on_device_cv/contracts.py` 의 Kotlin 포팅.
 *
 * Python PC 프로토타입과 **같은 공개 계약**을 유지한다. 값 의미가 달라지면
 * 양쪽을 함께 고쳐야 하며, JSON 키 이름은 백엔드/테스트가 의존하므로 바꾸지 않는다.
 *
 * 이 파일은 android.* 에 의존하지 않는다 → JVM 단위 테스트(`src/test`)에서 그대로 검증 가능.
 */

/** normalized `xyxy` 박스. 원본 upright 프레임 기준, 좌측 상단 원점, 모든 좌표 `[0, 1]`. */
data class BoundingBox(
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float,
) {
    init {
        require(xMin.isFinite() && yMin.isFinite() && xMax.isFinite() && yMax.isFinite()) {
            "Bounding-box coordinates must be finite"
        }
        require(xMin in 0f..1f && yMin in 0f..1f && xMax in 0f..1f && yMax in 0f..1f) {
            "Bounding box must be normalized to [0, 1]: ($xMin, $yMin, $xMax, $yMax)"
        }
        require(xMin < xMax && yMin < yMax) {
            "Bounding box must have positive area: ($xMin, $yMin, $xMax, $yMax)"
        }
    }

    val width: Float get() = xMax - xMin
    val height: Float get() = yMax - yMin

    /** 편차 계산(단계 5)이 바로 쓸 수 있는 기하 값. 여기서 정책 판단은 하지 않는다. */
    val centerX: Float get() = (xMin + xMax) / 2f
    val centerY: Float get() = (yMin + yMax) / 2f
    val area: Float get() = width * height

    fun iou(other: BoundingBox): Float {
        val intersectionWidth = (minOf(xMax, other.xMax) - maxOf(xMin, other.xMin)).coerceAtLeast(0f)
        val intersectionHeight =
            (minOf(yMax, other.yMax) - maxOf(yMin, other.yMin)).coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        val union = area + other.area - intersection
        return if (union > 0f) intersection / union else 0f
    }

    /** ⑥ 오버레이/포커스 좌표용 픽셀 변환. */
    fun toPixels(frameWidth: Int, frameHeight: Int): PixelRect {
        require(frameWidth > 0 && frameHeight > 0) { "Frame width and height must be positive" }
        return PixelRect(
            left = Math.round(xMin * frameWidth),
            top = Math.round(yMin * frameHeight),
            right = Math.round(xMax * frameWidth),
            bottom = Math.round(yMax * frameHeight),
        )
    }

    fun toJson(): String = buildString {
        append("{\"x_min\":").append(coordinate(xMin))
        append(",\"y_min\":").append(coordinate(yMin))
        append(",\"x_max\":").append(coordinate(xMax))
        append(",\"y_max\":").append(coordinate(yMax))
        append('}')
    }

    companion object {
        /**
         * 원시 좌표를 계약 범위로 clip 한다. 면적이 0 이하가 되거나 비정상 값이면 null.
         * detector adapter 가 모델 출력을 정리할 때 쓰는 진입점이다.
         */
        fun clipped(xMin: Float, yMin: Float, xMax: Float, yMax: Float): BoundingBox? {
            if (!xMin.isFinite() || !yMin.isFinite() || !xMax.isFinite() || !yMax.isFinite()) {
                return null
            }
            val left = xMin.coerceIn(0f, 1f)
            val top = yMin.coerceIn(0f, 1f)
            val right = xMax.coerceIn(0f, 1f)
            val bottom = yMax.coerceIn(0f, 1f)
            if (left >= right || top >= bottom) return null
            return BoundingBox(left, top, right, bottom)
        }
    }
}

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** detector 가 후처리를 끝낸 결과. 모델 텐서 포맷과 무관해야 한다. */
data class Detection(
    val label: String,
    val confidence: Float,
    val bbox: BoundingBox,
    val classId: Int? = null,
) {
    init {
        require(label.isNotBlank()) { "Detection label must not be empty" }
        require(confidence.isFinite() && confidence in 0f..1f) {
            "Detection confidence must be in [0, 1]: $confidence"
        }
        require(classId == null || classId >= 0) {
            "classId must be a non-negative integer when provided"
        }
    }
}

/** 스트림 내내 유지되는 `track_id` 가 붙은, 현재 프레임에서 실제로 관측된 객체. */
data class TrackedObject(
    val trackId: Int,
    val label: String,
    val confidence: Float,
    val bbox: BoundingBox,
    val classId: Int? = null,
) {
    init {
        require(trackId > 0) { "trackId must be a positive integer" }
        require(label.isNotBlank()) { "Tracked-object label must not be empty" }
        require(confidence.isFinite() && confidence in 0f..1f) {
            "Tracked-object confidence must be in [0, 1]: $confidence"
        }
        require(classId == null || classId >= 0) {
            "classId must be a non-negative integer when provided"
        }
    }

    fun toJson(): String = buildString {
        append("{\"track_id\":").append(trackId)
        append(",\"label\":\"").append(escapeJson(label)).append('"')
        append(",\"confidence\":").append(score(confidence))
        append(",\"bbox\":").append(bbox.toJson())
        append('}')
    }
}

/**
 * 프레임 1장의 공개 응답. **이게 ⑤/③ 로 넘기는 계약이다.**
 *
 * ```json
 * {"objects":[{"track_id":17,"label":"person","confidence":0.94,
 *              "bbox":{"x_min":0.31,"y_min":0.12,"x_max":0.68,"y_max":0.91}}]}
 * ```
 */
data class FrameResult(val objects: List<TrackedObject> = emptyList()) {
    init {
        require(objects.map { it.trackId }.toSet().size == objects.size) {
            "A frame cannot contain duplicate track IDs"
        }
    }

    val isEmpty: Boolean get() = objects.isEmpty()

    fun toJson(): String = objects.joinToString(
        separator = ",",
        prefix = "{\"objects\":[",
        postfix = "]}",
    ) { it.toJson() }

    companion object {
        val EMPTY = FrameResult()

        fun fromObjects(objects: Iterable<TrackedObject>): FrameResult =
            FrameResult(objects.sortedBy { it.trackId })
    }
}

/** bbox 는 6자리, confidence 는 4자리로 고정 직렬화한다 (로그 diff 안정성). */
private fun coordinate(value: Float): String = String.format(Locale.US, "%.6f", value)

private fun score(value: Float): String = String.format(Locale.US, "%.4f", value)

/** Objects365 label 은 `cabinet/shelf` 처럼 슬래시를 포함한다. 최소 escape 만 수행. */
private fun escapeJson(value: String): String {
    if (value.none { it == '"' || it == '\\' || it.code < 0x20 }) return value
    return buildString(value.length + 8) {
        for (character in value) {
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character == '\n' -> append("\\n")
                character == '\r' -> append("\\r")
                character == '\t' -> append("\\t")
                character.code < 0x20 -> append(String.format(Locale.US, "\\u%04x", character.code))
                else -> append(character)
            }
        }
    }
}
