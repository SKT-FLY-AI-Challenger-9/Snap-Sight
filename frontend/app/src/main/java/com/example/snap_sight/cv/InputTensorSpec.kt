package com.example.snap_sight.cv

/**
 * 모델 입력 텐서의 채널 배치.
 *
 * export 툴체인에 따라 갈린다. 틀리면 예외가 아니라 **색 채널이 뒤섞인 입력**이 들어가
 * 검출이 조용히 망가지므로 로드 시 shape 로 판별한다.
 */
enum class InputTensorLayout {
    /** `[1, H, W, 3]` — onnx2tf 계열 TFLite export (픽셀별 RGB 인터리브) */
    NHWC,

    /** `[1, 3, H, W]` — LiteRT-Torch 계열 export. PyTorch 레이아웃을 그대로 유지한다. */
    NCHW,
}

/**
 * 입력 텐서의 배치와 크기. 두 레이아웃을 stride 한 쌍으로 통일해 순회 코드를 하나로 유지한다.
 *
 * 배포 체크포인트를 ultralytics 8.4.x 로 export 하면 `[1, 3, 640, 640]` = [InputTensorLayout.NCHW] 다.
 */
data class InputTensorSpec(
    val layout: InputTensorLayout,
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "input size must be positive" }
    }

    val pixelCount: Int get() = width * height

    /** 다음 픽셀까지의 간격 (요소 단위). */
    val pixelStride: Int get() = if (layout == InputTensorLayout.NHWC) 3 else 1

    /** 다음 채널까지의 간격 (요소 단위). NCHW 는 채널이 평면으로 떨어져 있다. */
    val channelStride: Int get() = if (layout == InputTensorLayout.NHWC) 1 else pixelCount

    /**
     * @param pixelIndex `row * width + column`
     * @param channel 0=R, 1=G, 2=B
     * @return 입력 텐서 안의 요소 인덱스
     */
    fun indexOf(pixelIndex: Int, channel: Int): Int =
        pixelIndex * pixelStride + channel * channelStride

    companion object {
        /** @return 판별한 입력 규격, 해석 불가하면 null */
        fun of(shape: IntArray): InputTensorSpec? {
            if (shape.size != 4 || shape[0] != 1) return null
            return when {
                // 3채널 위치로 구분한다. 실제 입력 한 변이 3인 모델은 없으므로 모호하지 않다.
                shape[3] == 3 -> InputTensorSpec(InputTensorLayout.NHWC, shape[2], shape[1])
                shape[1] == 3 -> InputTensorSpec(InputTensorLayout.NCHW, shape[3], shape[2])
                else -> null
            }
        }
    }
}
