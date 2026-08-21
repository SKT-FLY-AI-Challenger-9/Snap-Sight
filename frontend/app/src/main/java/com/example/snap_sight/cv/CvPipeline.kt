package com.example.snap_sight.cv

/**
 * `ai/on_device_cv/pipeline.py` 의 Kotlin 포팅.
 *
 * detector -> (선택) extensions -> tracker -> 출력 threshold 순서만 담당하고
 * 모델·카메라·UI 는 전혀 모른다. 모델을 갈아끼워도 이 파일은 바뀌지 않는다.
 */
data class PipelineConfig(
    /** 공개 결과에 포함할 최소 confidence. 이보다 낮은 track 은 ID 유지에만 쓰인다. */
    val outputConfidenceThreshold: Float = 0.25f,
) {
    init {
        require(outputConfidenceThreshold in 0f..1f) {
            "outputConfidenceThreshold must be in [0, 1]"
        }
    }
}

class CvPipeline(
    private val detector: Detector,
    private val tracker: Tracker,
    private val extensions: List<DetectionExtension> = emptyList(),
    private val config: PipelineConfig = PipelineConfig(),
) {
    var isLoaded: Boolean = false
        private set

    /** 모델 로드. 실패하면 이미 로드된 리소스를 정리한 뒤 예외를 그대로 올린다. */
    fun load() {
        if (isLoaded) return
        val loadedExtensions = ArrayList<DetectionExtension>(extensions.size)
        try {
            detector.load()
            for (extension in extensions) {
                try {
                    extension.load()
                } catch (t: Throwable) {
                    extension.close()
                    throw t
                }
                loadedExtensions.add(extension)
            }
            isLoaded = true
        } catch (t: Throwable) {
            for (extension in loadedExtensions.asReversed()) {
                runCatching { extension.close() }
            }
            runCatching { detector.close() }
            throw t
        }
    }

    /**
     * 프레임 1장을 처리해 공개 계약을 반환한다.
     *
     * @param timestampS 카메라 프레임 시각(초). [Tracker] 의 계약을 따른다.
     * @param motionHint 직전 처리 이후 카메라 이동으로 인한 화면 내 이동량. [Tracker] 의 계약을 따른다.
     */
    fun process(frame: CvFrame, timestampS: Double? = null, motionHint: MotionHint? = null): FrameResult {
        check(isLoaded) { "CvPipeline.load() must be called before process()" }

        val primaryDetections = detector.detect(frame)
        val allDetections = if (extensions.isEmpty()) {
            primaryDetections
        } else {
            ArrayList<Detection>(primaryDetections).apply {
                for (extension in extensions) addAll(extension.extend(frame, primaryDetections))
            }
        }

        val trackedObjects = tracker.update(allDetections, timestampS, motionHint)
        val visibleObjects = trackedObjects
            .filter { it.confidence >= config.outputConfidenceThreshold }
            .sortedBy { it.trackId }
        return FrameResult(visibleObjects)
    }

    /** 새 카메라 세션 시작 전 track 상태 초기화. ID 가 다시 1부터 시작한다. */
    fun reset() {
        tracker.reset()
    }

    fun close() {
        if (!isLoaded) return
        for (extension in extensions.asReversed()) {
            runCatching { extension.close() }
        }
        runCatching { detector.close() }
        isLoaded = false
    }
}
