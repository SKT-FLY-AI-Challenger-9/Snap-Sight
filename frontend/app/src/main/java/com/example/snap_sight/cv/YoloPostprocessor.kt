package com.example.snap_sight.cv

/**
 * YOLO26 TFLite 출력 텐서를 [Detection] 목록으로 변환하는 순수 로직.
 * Android API 에 의존하지 않아 JVM 단위 테스트가 가능하다.
 *
 * YOLO26 은 NMS-free(end-to-end) 모델이라 출력이 이미 최종 탐지 목록이다:
 *   shape [1, N, 6], 각 행 = [x1, y1, x2, y2, score, classIndex]
 *   좌표는 모델 입력(letterbox 포함) 픽셀 기준.
 */
object YoloPostprocessor {

    /**
     * letterbox 파라미터: 원본(w×h)을 비율 유지로 축소해 정사각 입력(size×size)
     * 중앙에 놓았을 때의 배율과 여백.
     */
    data class Letterbox(val scale: Float, val padX: Float, val padY: Float)

    fun letterboxFor(srcWidth: Int, srcHeight: Int, inputSize: Int): Letterbox {
        val scale = minOf(inputSize.toFloat() / srcWidth, inputSize.toFloat() / srcHeight)
        val padX = (inputSize - srcWidth * scale) / 2f
        val padY = (inputSize - srcHeight * scale) / 2f
        return Letterbox(scale, padX, padY)
    }

    /**
     * end-to-end 출력([N,6], 입력 픽셀 좌표)을 정방향 원본 프레임 기준
     * 0..1 정규화 [Detection] 으로 되돌린다.
     *
     * @param rows        출력 텐서의 [N,6] 부분
     * @param inputSize   모델 입력 한 변 크기 (예: 320)
     * @param srcWidth    letterbox 이전 정방향 프레임 너비
     * @param srcHeight   letterbox 이전 정방향 프레임 높이
     * @param scoreThreshold 이 값 미만은 버린다
     */
    fun decode(
        rows: Array<FloatArray>,
        inputSize: Int,
        srcWidth: Int,
        srcHeight: Int,
        scoreThreshold: Float,
        labels: List<String> = CocoLabels.SNAKE_CASE,
    ): List<Detection> {
        val lb = letterboxFor(srcWidth, srcHeight, inputSize)
        val result = ArrayList<Detection>()
        for (row in rows) {
            if (row.size < 6) continue
            val score = row[4]
            if (score < scoreThreshold) continue
            val classIndex = row[5].toInt()
            val label = labels.getOrNull(classIndex) ?: continue

            // 입력 픽셀 → letterbox 제거 → 원본 픽셀 → 0..1 정규화
            val left = ((row[0] - lb.padX) / lb.scale / srcWidth).coerceIn(0f, 1f)
            val top = ((row[1] - lb.padY) / lb.scale / srcHeight).coerceIn(0f, 1f)
            val right = ((row[2] - lb.padX) / lb.scale / srcWidth).coerceIn(0f, 1f)
            val bottom = ((row[3] - lb.padY) / lb.scale / srcHeight).coerceIn(0f, 1f)
            if (right <= left || bottom <= top) continue

            result.add(Detection(label, score, left, top, right, bottom))
        }
        return result.sortedByDescending { it.score }
    }
}

/**
 * COCO 80 클래스. 인덱스는 YOLO 계열 학습 순서 그대로,
 * 이름은 `ai/target_spec_schema.md` 의 objectLabel 과 맞춘 snake_case.
 */
object CocoLabels {
    val SNAKE_CASE: List<String> = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
        "boat", "traffic_light", "fire_hydrant", "stop_sign", "parking_meter", "bench",
        "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
        "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports_ball", "kite", "baseball_bat", "baseball_glove",
        "skateboard", "surfboard", "tennis_racket", "bottle", "wine_glass", "cup",
        "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
        "broccoli", "carrot", "hot_dog", "pizza", "donut", "cake", "chair", "couch",
        "potted_plant", "bed", "dining_table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell_phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy_bear",
        "hair_drier", "toothbrush",
    )
}
