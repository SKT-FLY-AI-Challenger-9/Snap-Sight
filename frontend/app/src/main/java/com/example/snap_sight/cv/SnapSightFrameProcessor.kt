package com.example.snap_sight.cv

import android.content.Context
import android.os.SystemClock
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
    /**
     * 직전 분석 프레임 이후의 카메라 이동량을 돌려주는 공급자 (기능 1-C, 자이로 기반).
     * ACTIVE 카메라 프레임마다 1회 호출·소비되어 keyframe 사이 propagation에도 쓰인다.
     * null 이거나 null 을 돌려주면 보정 없음.
     */
    private val motionHintProvider: (() -> MotionHint?)? = null,
    /** 얼굴 신원 분석 훅 (기능 2). null 이면 신원 없이 동작한다. */
    private val faceAnalyzer: FaceFrameAnalyzer? = null,
    private val cadenceScheduler: AdaptiveDetectionScheduler =
        AdaptiveDetectionScheduler(config.adaptiveDetectionConfig),
) : FrameProcessor {

    private enum class LoadState { PENDING, READY, FAILED }

    /** ① STT/NLU 연결 지점. 분석 스레드에서 읽으므로 volatile. */
    private val targetIntentState = TargetIntentState()
    private val targetUpdateLock = Any()

    /**
     * 의도가 등록 인물·사물 이름을 가리킬 때("유재석 찍어줘") 그 이름. null 이 아니면 의도 후보를
     * 그 이름으로 식별된 track 으로 한 번 더 좁혀 편차 안내가 엉뚱한 사람을 따라가지 않게 한다
     * (2026-08-23 실기기: 오버레이는 유재석만 보이는데 안내는 다른 사람을 향하던 문제).
     * 아직 식별 전이면 후보 0개(SEARCHING) — 안내는 "찾는 중"으로 남는다. 세션 시작 시 null 로 초기화.
     */
    var targetIdentityName: String?
        get() = targetIntentState.current().identityName
        set(value) {
            synchronized(targetUpdateLock) {
                val previous = targetIntentState.current()
                if (previous.identityName != value) deviationCalculator.reset()
                val updated = targetIntentState.setIdentityName(value)
                if (updated.generation != previous.generation) cadenceScheduler.reset()
            }
        }

    @Volatile
    private var loadState = LoadState.PENDING

    @Volatile
    private var pendingReset = false

    /**
     * 분석 예산 모드 (발열 대책 P1, 2026-08-22). MainActivity 가 세션 상태에 따라 바꾼다.
     * - [AnalysisMode.OFF]: 카메라는 돌지만 추론하지 않는다 (홈·결과 화면 등 조준하지 않는 동안)
     * - [AnalysisMode.ACTIVE]: [FrameProcessorConfig.minAnalysisIntervalMs] 주기로 추론한다
     * 기본 ACTIVE — 모드를 모르는 호출자는 기존처럼 동작한다.
     */
    @Volatile
    var analysisMode: AnalysisMode = AnalysisMode.ACTIVE

    /**
     * 열 적응 배수 — 기기가 뜨거워지면 MainActivity 가 1 보다 크게 올린다 (추론 간격 = 기본 × 배수).
     * 1 이면 적응 없음.
     */
    @Volatile
    var thermalSlowdown: Float = 1f

    /** 분석 스레드 전용 상태 — 다른 스레드에서 건드리지 않는다. */
    private var frameCounter = 0L
    private var lastDetectorElapsedMs: Long? = null
    private var lastTimestampS = Double.NEGATIVE_INFINITY
    private var lastFrameResult = FrameResult.EMPTY
    private var lastIdentities: Map<Int, String> = emptyMap()

    /**
     * 세션 의도를 설정한다. **null 허용이 계약이다** — 마이크 권한이 없거나 발화를 건너뛴
     * 세션에서는 의도 자체가 없다 (`CaptureSessionManager.startSession()` 참고).
     * 다음 프레임부터 selector 가 이 스펙으로 후보를 고른다. tracking 상태는 건드리지 않으므로
     * 세션 중 의도가 바뀌어도 기존 `track_id` 는 유지된다. 공개 `objects` 계약도 영향 없다.
     */
    fun setTargetSpec(spec: TargetSpec?) {
        synchronized(targetUpdateLock) {
            val previous = targetIntentState.current()
            if (previous.spec != spec) deviationCalculator.reset()
            val updated = targetIntentState.setSpec(spec)
            if (updated.generation != previous.generation) cadenceScheduler.reset()
        }
        Log.i(TAG, "TargetSpec 설정: ${spec?.let { "${it.sessionId}/${it.subjectType.wire}" } ?: "없음(null)"}")
    }

    /** Applies the spec and local registered-name filter as one target generation. */
    fun setTargetIntent(
        spec: TargetSpec?,
        identityName: String?,
        forceNewGeneration: Boolean = false,
    ): Long {
        val updated = synchronized(targetUpdateLock) {
            val previous = targetIntentState.current()
            val advances = forceNewGeneration ||
                previous.spec != spec || previous.identityName != identityName
            if (advances) deviationCalculator.reset()
            targetIntentState.set(
                spec = spec,
                identityName = identityName,
                forceNewGeneration = forceNewGeneration,
            ).also {
                if (it.generation != previous.generation) cadenceScheduler.reset()
            }
        }
        Log.i(
            TAG,
            "Target intent 설정: generation=${updated.generation}, " +
                "status=${spec?.status?.wire}, type=${spec?.subjectType?.wire}, " +
                "identity=${identityName != null}",
        )
        return updated.generation
    }

    /** ① STT 응답 JSON 을 그대로 넘기는 편의 경로. 깨진 payload 는 조용히 무시된다. */
    fun setTargetSpecJson(json: String?) {
        setTargetSpec(TargetSpec.fromJsonOrNull(json) { Log.w(TAG, "TargetSpec 파싱 실패 — 무시함", it) })
    }

    fun currentTargetSpec(): TargetSpec? = targetIntentState.current().spec

    fun currentTargetIntentGeneration(): Long = targetIntentState.current().generation

    fun isCurrentTargetIntentGeneration(generation: Long): Boolean =
        targetIntentState.isCurrent(generation)

    /**
     * 새 촬영 세션 시작 시 호출. track 상태를 지워 `track_id` 가 다시 1부터 시작한다.
     * 분석 스레드 밖에서 불릴 수 있으므로 다음 [onFrame] 에서 반영한다.
     */
    fun startNewSession(spec: TargetSpec? = null) {
        // Publish the reset request before the new generation. If the analysis thread races,
        // it can only emit the old generation (discarded by Main) or reset before using new.
        pendingReset = true
        synchronized(targetUpdateLock) {
            deviationCalculator.reset()
            targetIntentState.set(
                spec = spec,
                identityName = null,
                forceNewGeneration = true,
            )
            cadenceScheduler.reset()
        }
    }

    override fun onAttached() {
        Log.i(TAG, "CV 프로세서 연결됨 — 모델은 첫 프레임에서 로드")
    }

    override fun onFrame(image: ImageProxy, rotationDegrees: Int) {
        if (pendingReset) {
            pendingReset = false
            frameCounter = 0
            lastDetectorElapsedMs = null
            lastTimestampS = Double.NEGATIVE_INFINITY
            lastFrameResult = FrameResult.EMPTY
            lastIdentities = emptyMap()
            runCatching { pipeline.reset() }
            runCatching { faceAnalyzer?.reset() }
        }

        // Freeze intent at frame start. Reading it in emit() would let a mid-frame intent
        // update relabel old detector/calculator work with the new generation.
        val frameIntent = targetIntentState.current()
        val timestampMs = System.currentTimeMillis()
        if (!ensureLoaded()) {
            emit(FrameResult.EMPTY, timestampMs, analyzed = false, intent = frameIntent)
            return
        }

        // 조준하지 않는 동안은 추론 자체를 하지 않는다 — 상시 CPU 추론이 발열의 주범이었다.
        // 모델 로드(ensureLoaded)는 위에서 먼저 해 두어 조준 시작 시 지연이 없게 한다.
        if (analysisMode == AnalysisMode.OFF) return

        // 추론 주기: ACTIVE는 SEARCHING/LOCKED/LOST 상태와 경과시간으로, ENROLL은 고정 시간으로
        // 정한다. interval=0인 legacy 설정에서만 frame stride를 사용한다.
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val baseIntervalMs = if (analysisMode == AnalysisMode.ENROLL) {
            config.enrollAnalysisIntervalMs
        } else {
            config.minAnalysisIntervalMs
        }
        val timeReady = if (analysisMode == AnalysisMode.ENROLL) {
            val previous = lastDetectorElapsedMs
            previous == null || nowElapsedMs < previous || nowElapsedMs - previous >= baseIntervalMs
        } else {
            cadenceScheduler.shouldRunDetector(
                nowMs = nowElapsedMs,
                lastDetectorAtMs = lastDetectorElapsedMs,
                baseIntervalMs = baseIntervalMs,
                thermalSlowdown = thermalSlowdown,
            )
        }
        val legacyStrideReady = baseIntervalMs > 0L ||
            frameCounter % config.analyzeEveryNthFrame == 0L
        val shouldAnalyze = timeReady && legacyStrideReady
        frameCounter++
        val timestampS = nextTimestampS(image)
        val motionHint = motionHintProvider?.invoke()
        if (!shouldAnalyze) {
            val predicted = runCatching { pipeline.predictOnly(timestampS, motionHint) }
                .getOrElse { t ->
                    Log.w(TAG, "tracker propagation 실패 — 직전 결과를 유지", t)
                    FrameResult.EMPTY
                }
            if (predicted.objects.isNotEmpty()) {
                lastFrameResult = predicted
                if (config.emitHeldResults) {
                    emit(predicted, timestampMs, analyzed = false, intent = frameIntent)
                }
            } else if (config.emitHeldResults) {
                emit(lastFrameResult, timestampMs, analyzed = false, held = true, intent = frameIntent)
            }
            return
        }
        lastDetectorElapsedMs = nowElapsedMs

        val frameResult = try {
            val frame = converter.convert(image, rotationDegrees)
            val result = pipeline.process(
                frame,
                timestampS = timestampS,
                motionHint = motionHint,
            )
            // 얼굴 신원(기능 2)은 tracking 뒤, frame 버퍼가 유효한 동안에만 계산 가능하다.
            // 실패해도 CV 스트림은 계속 돌아야 하므로 삼킨다.
            faceAnalyzer?.let { analyzer ->
                lastIdentities = runCatching { analyzer.analyze(frame, result) }
                    .getOrElse { t ->
                        Log.w(TAG, "얼굴 신원 분석 실패 — 이 프레임은 신원 없이 진행", t)
                        lastIdentities
                    }
            }
            result
        } catch (t: Throwable) {
            // 한 프레임 실패로 스트림 전체를 죽이지 않는다. 다음 프레임에서 다시 시도.
            Log.w(TAG, "프레임 처리 실패 — 이 프레임은 건너뜀", t)
            emit(lastFrameResult, timestampMs, analyzed = false, held = true, intent = frameIntent)
            return
        }

        lastFrameResult = frameResult
        emit(
            frameResult,
            timestampMs,
            analyzed = true,
            cadenceNowMs = nowElapsedMs,
            intent = frameIntent,
        )
    }

    override fun onDetached() {
        runCatching { pipeline.close() }
        cadenceScheduler.reset()
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

    private fun emit(
        frameResult: FrameResult,
        timestampMs: Long,
        analyzed: Boolean,
        held: Boolean = false,
        cadenceNowMs: Long? = null,
        intent: TargetIntentSnapshot,
    ) {
        val output = synchronized(targetUpdateLock) {
            // Serialize post-processing with target publication. A stale frame either finishes
            // before reset/publication or exits without mutating calculator/scheduler state.
            if (!targetIntentState.isCurrent(intent.generation)) return
            val spec = intent.spec
            var selection = selector.select(frameResult, spec)
            // 이름 의도: 의도 후보 중 그 이름으로 식별된 track 만 남긴다. 스펙이 아직 없어(DISABLED,
            // 백엔드 대기 중) 후보가 전체 객체일 때도 적용한다 — 이름은 STT 직후 이미 정해져 있다.
            val name = intent.identityName
            selection = RegisteredIdentitySelection.apply(
                frameResult = frameResult,
                base = selection,
                spec = spec,
                identityName = name,
                identities = lastIdentities,
            )
            if (analyzed && cadenceNowMs != null) {
                cadenceScheduler.onDetectorResult(selection, cadenceNowMs)
            }
            // cached bbox를 다시 선택기에 넣으면 fresh 관측처럼 lock 시간이 연장되므로, 편차 계산에는
            // 빈 후보를 주어 calculator 자체의 시간 기반 HELD 경로를 사용한다.
            val deviationSelection = if (held) selection.copy(candidates = emptyList()) else selection
            val freshness = when {
                held -> ObservationFreshness.HELD
                selection.candidates.any { it.freshness == ObservationFreshness.FRESH } ->
                    ObservationFreshness.FRESH
                selection.candidates.any { it.freshness == ObservationFreshness.PREDICTED } ->
                    ObservationFreshness.PREDICTED
                analyzed -> ObservationFreshness.FRESH
                else -> ObservationFreshness.HELD
            }
            CvFrameOutput(
                frameResult = frameResult,
                timestampMs = timestampMs,
                analyzed = analyzed,
                observationFreshness = freshness,
                targetIntentGeneration = intent.generation,
                targetSpec = spec,
                targetIdentityName = name,
                selection = selection,
                deviation = deviationCalculator.compute(deviationSelection, spec),
                identities = lastIdentities,
            )
        }
        // Never call Main while holding targetUpdateLock: Main applies intents while holding
        // its own targetIntentLock, and the opposite lock order would deadlock.
        listener.onFrameResult(output)
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
            motionHintProvider: (() -> MotionHint?)? = null,
            faceAnalyzer: FaceFrameAnalyzer? = null,
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
                motionHintProvider = motionHintProvider,
                faceAnalyzer = faceAnalyzer,
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

/**
 * [SnapSightFrameProcessor.analysisMode] 값.
 * - OFF: 조준하지 않는 동안 추론을 끈다
 * - ACTIVE: [FrameProcessorConfig.minAnalysisIntervalMs] 주기(+열 적응)로 추론
 * - ENROLL: [FrameProcessorConfig.enrollAnalysisIntervalMs] 고정 주기, 열 적응 없음 — 등록 스캔용.
 *   샘플 수보다 각도 다양성이 중요하므로 모든 프레임을 받지 않고 일정 간격으로만 고른다.
 */
enum class AnalysisMode { OFF, ACTIVE, ENROLL }

data class FrameProcessorConfig(
    /** min interval이 0인 legacy 모드에서만 N 프레임마다 1번 추론한다. */
    val analyzeEveryNthFrame: Int = 1,
    /**
     * 연속 추론 사이의 최소 간격(ms). 0 이면 stride 만 적용(기존 동작). 카메라 fps 와 무관하게
     * 추론 빈도를 고정하는 용도 — 200 이면 최대 5Hz. 열 적응 배수가 곱해진다.
     */
    val minAnalysisIntervalMs: Long = 0L,
    /** [AnalysisMode.ENROLL] 에서의 추론 간격(ms). 열 적응을 받지 않는다. */
    val enrollAnalysisIntervalMs: Long = 0L,
    /** stride 로 건너뛴 프레임에도 직전 결과를 `analyzed=false` 로 내보낼지. */
    val emitHeldResults: Boolean = true,
    /**
     * YUV→RGB 변환 시 긴 변의 상한. detector 가 어차피 letterbox 로 줄이므로
     * 그 전에 솎아내면 변환 비용이 크게 준다. 0 이면 원본 해상도를 유지한다.
     */
    val maxAnalysisDimension: Int = 640,
    /** ACTIVE 모드의 SEARCHING/LOCKED/LOST detector cadence. */
    val adaptiveDetectionConfig: AdaptiveDetectionConfig = AdaptiveDetectionConfig(),
) {
    init {
        require(analyzeEveryNthFrame >= 1) { "analyzeEveryNthFrame must be at least 1" }
        require(minAnalysisIntervalMs >= 0L) { "minAnalysisIntervalMs must be non-negative" }
        require(enrollAnalysisIntervalMs >= 0L) { "enrollAnalysisIntervalMs must be non-negative" }
        require(maxAnalysisDimension >= 0) { "maxAnalysisDimension must be non-negative" }
    }
}
