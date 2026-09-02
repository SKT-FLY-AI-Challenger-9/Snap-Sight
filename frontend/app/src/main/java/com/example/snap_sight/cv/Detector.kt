package com.example.snap_sight.cv

/**
 * 회전 보정이 끝난 upright RGB888 프레임.
 *
 * Python 쪽 `validate_bgr_frame(frame_bgr)` 계약과 대응한다. 색 순서만 다르다:
 * PC 는 OpenCV BGR, Android 는 TFLite 모델 입력에 맞춘 **RGB** 다.
 * bbox 는 항상 이 프레임 기준으로 정규화되므로 하위 계약에는 영향이 없다.
 *
 * [rgb] 버퍼는 [YuvToRgbConverter] 가 프레임마다 재사용한다.
 * `onFrame` 이 리턴한 뒤에는 내용이 덮어써지므로 밖으로 들고 나가면 안 된다.
 */
class CvFrame(
    @JvmField val rgb: ByteArray,
    @JvmField val width: Int,
    @JvmField val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "frame must not be empty" }
        require(rgb.size >= width * height * 3) {
            "rgb buffer too small: ${rgb.size} < ${width * height * 3}"
        }
    }

    /** 픽셀 (x, y) 의 RGB 시작 오프셋. */
    fun offsetOf(x: Int, y: Int): Int = (y * width + x) * 3
}

/**
 * 모델 런타임과 무관한 detector 계약.
 *
 * 구현체 안에서 **끝내야 하는 것**: 색 변환, letterbox/resize, quantize/dequantize,
 * raw tensor decode, NMS, class ID → label 매핑.
 * 밖으로는 [Detection] 만 나간다. (Python `detectors/base.py` 와 동일한 경계)
 */
interface Detector {
    /** 모델 로드. 실패 시 [ModelUnavailableException] 등을 던진다. */
    fun load()

    /** 이 프레임의 후처리 완료된 검출 결과. */
    fun detect(frame: CvFrame): List<Detection>

    /** 모델 리소스 해제. 여러 번 불려도 안전해야 한다. */
    fun close()
}

/**
 * 모델 자산이 아직 없거나(assets 에 .tflite 미배치) 로드에 실패한 경우.
 *
 * [SnapSightFrameProcessor] 는 이 예외를 잡아서 앱을 죽이지 않고
 * "검출 0개" 로 계속 동작한다 — 카메라·세션·업로드 경로는 그대로 검증 가능해야 하므로.
 */
class ModelUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** 모델 없이 파이프라인 배선만 검증할 때 쓰는 명시적 placeholder. */
class NoOpDetector : Detector {
    override fun load() = Unit
    override fun detect(frame: CvFrame): List<Detection> = emptyList()
    override fun close() = Unit
}

/**
 * tracking 앞단에 끼워 넣는 선택적 검출 단계.
 *
 * 향후 face 기능의 자리: primary 검출 중 `person` bbox 만 crop 해서 face detector 를 돌리고,
 * face box 를 원본 프레임 normalized 좌표로 되돌린 `Detection(label = "face", ...)` 를 반환하면 된다.
 * 파이프라인과 tracker 의 공개 출력 계약은 바뀌지 않는다.
 */
interface DetectionExtension {
    fun load() {}

    fun extend(frame: CvFrame, primaryDetections: List<Detection>): List<Detection>

    fun close() {}
}
