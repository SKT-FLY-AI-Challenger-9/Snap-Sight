package com.example.snap_sight.cv

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy

/**
 * ② 온디바이스 CV 모듈의 Android 진입점. [FrameProcessor] 계약을 구현한다.
 *
 * 흐름: `ImageProxy(YUV_420_888)` → [YuvToRgbConverter] → [CvPipeline] → [TargetSelector]
 *       → [DeviationCalculator] → [ObjectStreamListener]
 *
 * ⑤ 쪽 배선은 한 줄이다:
 * ```kotlin
 * val cv = SnapSightFrameProcessor.create(context) { output ->
 *     Log.d("SnapSightCV", output.objectsJson())
 * }
 * cameraController.setFrameProcessor(cv)
 * ```
 *
 * 설계 규칙:
 *  - 모델 자산이 없어도 앱은 죽지 않는다. 로드 실패 시 "검출 0개" 로 계속 돌면서
 *    카메라·세션·업로드 경로를 그대로 검증할 수 있게 한다.
 *  - 의도(TargetSpec)는 [setTargetSpec] 로 받고 [Objects365TargetSelector] 가 tracking 뒤에
 *    후보를 고른다. 항상 null 일 수 있으며, null 세션은 전체 객체를 그대로 내보낸다.
 *  - 편차 계산은 [DeviationCalculator] 로 분리돼 있고 기본값은 아무것도 하지 않는다.
 */
class SnapSightFrameProcessor(
    private val pipeline: CvPipeline,
    private val listener: ObjectStreamListener,
    private val config: FrameProcessorConfig = FrameProcessorConfig(),
    private val selector: TargetSelector = PassThroughTargetSelector(),
    private val deviationCalculator: DeviationCalculator = NoDeviationCalculator(),
    private val converter: YuvToRgbConverter = YuvToRgbConverter(config.maxAnalysisDimension),
) : FrameProcessor {

    private enum class LoadState { PENDING, READY, FAILED }

    /** ① STT/NLU 연결 지점. 분석 스레드에서 읽으므로 volatile. */
    @Volatile
    private var targetSpec: TargetSpec? = null

    @Volatile
    private var loadState = LoadState.PENDING

    @Volatile
    private var pendingReset = false

    /** 분석 스레드 전용 상태 — 다른 스레드에서 건드리지 않는다. */
    private var frameCounter = 0L
    private var lastTimestampS = Double.NEGATIVE_INFINITY
    private var lastFrameResult = FrameResult.EMPTY

    /**
     * 세션 의도를 설정한다. **null 허용이 계약이다** — 마이크 권한이 없거나 발화를 건너뛴
     * 세션에서는 의도 자체가 없다 (`CaptureSessionManager.startSession()` 참고).
     * 다음 프레임부터 selector 가 이 스펙으로 후보를 고른다. tracking 상태는 건드리지 않으므로
     * 세션 중 의도가 바뀌어도 기존 `track_id` 는 유지된다. 공개 `objects` 계약도 영향 없다.
     */
    fun setTargetSpec(spec: TargetSpec?) {
        targetSpec = spec
        Log.i(TAG, "TargetSpec 설정: ${spec?.let { "${it.sessionId}/${it.subjectType.wire}" } ?: "없음(null)"}")
    }

    /** ① STT 응답 JSON 을 그대로 넘기는 편의 경로. 깨진 payload 는 조용히 무시된다. */
    fun setTargetSpecJson(json: String?) {
        setTargetSpec(TargetSpec.fromJsonOrNull(json) { Log.w(TAG, "TargetSpec 파싱 실패 — 무시함", it) })
    }

    fun currentTargetSpec(): TargetSpec? = targetSpec

    /**
     * 새 촬영 세션 시작 시 호출. track 상태를 지워 `track_id` 가 다시 1부터 시작한다.
     * 분석 스레드 밖에서 불릴 수 있으므로 다음 [onFrame] 에서 반영한다.
     */
    fun startNewSession(spec: TargetSpec? = null) {
        setTargetSpec(spec)
        pendingReset = true
    }

    override fun onAttached() {
        Log.i(TAG, "CV 프로세서 연결됨 — 모델은 첫 프레임에서 로드")
    }

    override fun onFrame(image: ImageProxy, rotationDegrees: Int) {
        if (pendingReset) {
            pendingReset = false
            frameCounter = 0
            lastTimestampS = Double.NEGATIVE_INFINITY
            lastFrameResult = FrameResult.EMPTY
            runCatching { pipeline.reset() }
        }

        val timestampMs = System.currentTimeMillis()
        if (!ensureLoaded()) {
            emit(FrameResult.EMPTY, timestampMs, analyzed = false)
            return
        }

        // 매 프레임 추론이 버거우면 stride 로 건너뛰고 직전 결과를 유지한다.
        // ⑥ 피드백 루프가 끊기지 않도록 건너뛴 프레임도 analyzed=false 로 내보낸다.
        val shouldAnalyze = frameCounter % config.analyzeEveryNthFrame == 0L
        frameCounter++
        if (!shouldAnalyze) {
            if (config.emitHeldResults) emit(lastFrameResult, timestampMs, analyzed = false)
            return
        }

        val frameResult = try {
            val frame = converter.convert(image, rotationDegrees)
            pipeline.process(frame, timestampS = nextTimestampS(image))
        } catch (t: Throwable) {
            // 한 프레임 실패로 스트림 전체를 죽이지 않는다. 다음 프레임에서 다시 시도.
            Log.w(TAG, "프레임 처리 실패 — 이 프레임은 건너뜀", t)
            emit(lastFrameResult, timestampMs, analyzed = false)
            return
        }

        lastFrameResult = frameResult
        emit(frameResult, timestampMs, analyzed = true)
    }

    override fun onDetached() {
        runCatching { pipeline.close() }
        loadState = LoadState.PENDING
        Log.i(TAG, "CV 프로세서 분리됨")
    }

    /** 모델 로드는 메인 스레드를 막지 않도록 첫 프레임(분석 스레드)에서 수행한다. */
    private fun ensureLoaded(): Boolean {
        when (loadState) {
            LoadState.READY -> return true
            LoadState.FAILED -> return false
            LoadState.PENDING -> Unit
        }
        return try {
            pipeline.load()
            loadState = LoadState.READY
            true
        } catch (t: Throwable) {
            loadState = LoadState.FAILED
            Log.e(TAG, "CV 모델 로드 실패 — 검출 없이 계속 진행함", t)
            false
        }
    }

    /**
     * tracker 는 스트림 안에서 단조 증가하는 timestamp 를 요구한다.
     * CameraX 가 같은 값이나 역행 값을 주더라도 예외로 스트림을 끊지 않고 최소 폭만큼 밀어준다.
     */
    private fun nextTimestampS(image: ImageProxy): Double {
        val cameraTimestampS = image.imageInfo.timestamp / 1_000_000_000.0
        val timestampS = if (lastTimestampS.isFinite() && cameraTimestampS <= lastTimestampS) {
            lastTimestampS + MIN_TIMESTAMP_STEP_S
        } else {
            cameraTimestampS
        }
        lastTimestampS = timestampS
        return timestampS
    }

    private fun emit(frameResult: FrameResult, timestampMs: Long, analyzed: Boolean) {
        val spec = targetSpec
        val selection = selector.select(frameResult, spec)
        listener.onFrameResult(
            CvFrameOutput(
                frameResult = frameResult,
                timestampMs = timestampMs,
                analyzed = analyzed,
                targetSpec = spec,
                selection = selection,
                deviation = deviationCalculator.compute(selection, spec),
            )
        )
    }

    companion object {
        private const val TAG = "SnapSightCV"
        private const val MIN_TIMESTAMP_STEP_S = 1e-6

        /**
         * 기본 구성(TFLite detector + ByteTrackLite tracker)으로 프로세서를 만든다.
         * 모델 자산이 없으면 첫 프레임에서 로드에 실패하고 빈 결과를 계속 내보낸다.
         *
         * selector 를 넘기지 않으면 detector 와 같은 라벨 자산으로 [Objects365TargetSelector] 를
         * 만든다. 라벨 자산이 없거나 깨져 있으면 [PassThroughTargetSelector] 로 물러난다 —
         * 의도 해석이 안 된다고 카메라 루프가 죽으면 안 되기 때문이다.
         */
        fun create(
            context: Context,
            listener: ObjectStreamListener,
            config: FrameProcessorConfig = FrameProcessorConfig(),
            detectorConfig: TfLiteDetectorConfig = TfLiteDetectorConfig(),
            trackerConfig: ByteTrackLiteConfig = ByteTrackLiteConfig(),
            pipelineConfig: PipelineConfig = PipelineConfig(),
            extensions: List<DetectionExtension> = emptyList(),
            selector: TargetSelector? = null,
            deviationCalculator: DeviationCalculator = NoDeviationCalculator(),
        ): SnapSightFrameProcessor {
            if (detectorConfig.minimumConfidence != trackerConfig.minimumMatchingConfidence) {
                Log.w(
                    TAG,
                    "detector(${detectorConfig.minimumConfidence})와 " +
                            "tracker(${trackerConfig.minimumMatchingConfidence})의 최소 confidence 가 다름 " +
                            "— 저신뢰 검출을 이용한 ID 복구가 의도대로 동작하지 않을 수 있음",
                )
            }
            return SnapSightFrameProcessor(
                pipeline = CvPipeline(
                    detector = TfLiteYoloDetector(context.applicationContext, detectorConfig),
                    tracker = ByteTrackLiteTracker(trackerConfig),
                    extensions = extensions,
                    config = pipelineConfig,
                ),
                listener = listener,
                config = config,
                selector = selector ?: defaultSelector(context, detectorConfig.labelsAsset),
                deviationCalculator = deviationCalculator,
            )
        }

        /**
         * detector 와 **같은** 라벨 자산으로 taxonomy 를 만들어 selector 와 detector 의
         * class ID 해석이 어긋날 수 없게 한다. 자산이 없으면 pass-through 로 물러난다.
         */
        private fun defaultSelector(context: Context, labelsAsset: String): TargetSelector =
            try {
                val raw = context.applicationContext.assets.open(labelsAsset)
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                Objects365TargetSelector(ObjectTaxonomy.fromLabelsText(raw))
            } catch (t: Throwable) {
                Log.w(TAG, "라벨 자산($labelsAsset)으로 taxonomy 생성 실패 — 의도 선택 없이 pass-through 로 동작", t)
                PassThroughTargetSelector()
            }
    }
}

data class FrameProcessorConfig(
    /** N 프레임마다 1번만 추론한다. 1 이면 매 프레임. */
    val analyzeEveryNthFrame: Int = 1,
    /** stride 로 건너뛴 프레임에도 직전 결과를 `analyzed=false` 로 내보낼지. */
    val emitHeldResults: Boolean = true,
    /**
     * YUV→RGB 변환 시 긴 변의 상한. detector 가 어차피 letterbox 로 줄이므로
     * 그 전에 솎아내면 변환 비용이 크게 준다. 0 이면 원본 해상도를 유지한다.
     */
    val maxAnalysisDimension: Int = 640,
) {
    init {
        require(analyzeEveryNthFrame >= 1) { "analyzeEveryNthFrame must be at least 1" }
        require(maxAnalysisDimension >= 0) { "maxAnalysisDimension must be non-negative" }
    }
}
