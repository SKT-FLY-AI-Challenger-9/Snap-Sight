// 이 파일: 카메라를 켜고 끄고 사진을 찍는 심장부.
// 미리보기 연결, 초점·노출 조절, 촬영 요청까지 카메라 관련 일을 전부 여기서 처리한다.
// 다른 모듈은 카메라를 직접 만지지 않고 이 클래스만 부르면 된다.
package com.example.snap_sight.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import com.example.snap_sight.cv.FrameProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ⑤ 카메라/센서 통합 모듈의 진입점.
 *
 * 책임:
 *  - CameraX 파이프라인(미리보기 + 프레임 분석 + 사진 촬영) 바인딩
 *  - 연속 자동초점(기본) + 좌표 지시형 초점 + 노출 보정
 *  - 전/후면 렌즈 전환
 *  - 프레임 스트림을 [FrameProcessor](② CV 모듈)와 링 버퍼에 전달
 *  - 촬영 결과를 [CaptureEventListener] 로 전달
 *
 * 사용 순서: 생성 → [setFrameProcessor]/[captureEventListener] 연결 → [start] → [takePhoto] → [shutdown]
 */
class CameraController(private val context: Context) {

    /**
     * 분석 스트림 운용 단계.
     *
     * [OFF]는 ImageAnalysis use case 자체를 해제해 ISP/YUV 비용을 없애고, [WARM]은 use case만
     * 묶어 둔 채 analyzer를 떼어 빠른 재개를 허용한다. [ACTIVE]에서만 CV/링 버퍼에 프레임을 전달한다.
     */
    enum class AnalysisMode { OFF, WARM, ACTIVE }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var analyzerAttached = false
    private var boundWithViewPort = false

    private val bindingLock = Any()
    private var bindingGeneration = 0L
    private var boundLifecycleOwner: LifecycleOwner? = null
    private var boundPreviewView: PreviewView? = null

    @Volatile
    var analysisMode: AnalysisMode = AnalysisMode.ACTIVE
        private set

    private val captureGeneration = AtomicLong(0L)

    // ---- 가로모드 (2026-08-24, 분석 스트림 확장 2026-08-31) ----
    // UI(Activity)는 세로 고정이지만, 기기를 가로로 눕혀 찍으면 사진은 든 방향대로
    // 똑바로 저장돼야 한다. 화면 회전이 잠겨 있어 display rotation 이 항상 0 이므로,
    // 물리 방향 센서로 ImageCapture.targetRotation 을 따라가게 한다.
    // 분석 스트림(ImageAnalysis)도 같은 회전을 따른다 (2026-08-31) — 가로로 들면 YOLO·ML Kit 이
    // 90° 누운 피사체를 봐서 인식률이 무너지고, 서류 quad 좌표가 촬영본과 어긋나던 문제의 근본
    // 원인. 이제 CV 프레임(rotationDegrees 반영 후)은 항상 사용자 기준 정방향이고, 좌표계가
    // 촬영본(EXIF upright)과 일치한다. 오버레이는 UprightFrameMapping 으로 화면 좌표로 되돌린다.

    /** 마지막으로 관측된 기기 물리 방향의 Surface 회전값. 재바인딩 시 초기값으로도 쓴다. */
    private var deviceRotation: Int = Surface.ROTATION_0

    // 촬영 화면 UI 요소 제자리 회전용 (2026-08-31) — 촬영 회전과 같은 센서 판정을 그대로 노출해
    // "찍히는 방향"과 "화면 요소가 도는 방향"이 어긋나지 않게 한다. 센서는 카메라가 바인딩된
    // 동안(start~shutdown)만 켜져 있으므로 자연히 촬영 중에만 갱신된다.
    private val _deviceRotationFlow = MutableStateFlow(Surface.ROTATION_0)
    val deviceRotationFlow: StateFlow<Int> = _deviceRotationFlow.asStateFlow()

    private val orientationListener by lazy {
        object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rotation == deviceRotation) return
                deviceRotation = rotation
                _deviceRotationFlow.value = rotation
                Log.i(TAG, "기기 방향 변경 → 촬영·분석 회전 ${rotation * 90}°")
                imageCapture?.targetRotation = rotation
                imageAnalysis?.targetRotation = rotation
            }
        }
    }

    // release() 이후 다시 start() 되는 경우에 대비해 재생성 가능하게 var 로 둔다
    private var analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameAnalysisAdapter = FrameAnalysisAdapter()

    /** 촬영 이벤트 수신자. 언제든 교체 가능. */
    var captureEventListener: CaptureEventListener? = null

    /**
     * 연사 승자 후처리 훅 (2026-08-25) — 새 파일을 돌려주면 그 파일을 게시하고, null 이면
     * 원본 그대로. MainActivity 가 인물 세션일 때 [PortraitAutoCrop] 을 연결한다.
     * 연사 채점 스레드에서 호출되므로 블로킹 작업이 허용된다.
     */
    @Volatile
    var burstFinisher: ((File) -> File?)? = null

    /** 후면 카메라인가 — 수평 보정([HorizonStraightener]) 등 부호가 렌즈 방향에 묶인 후처리의 게이트. */
    val isBackLens: Boolean get() = lensFacing == CameraSelector.LENS_FACING_BACK

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    val isBound: Boolean get() = camera != null

    /** ② CV 모듈 연결. null 을 주면 분리. 카메라 동작 중에도 교체 가능. */
    fun setFrameProcessor(processor: FrameProcessor?) {
        frameAnalysisAdapter.setProcessor(processor)
    }

    /** 링 버퍼 등 보조 프레임 소비자 연결. CV 프로세서와 독립적으로 동작한다. */
    fun setFrameSink(sink: FrameSink?) {
        frameAnalysisAdapter.setSink(sink)
    }

    /**
     * 화면/세션 상태에 맞춰 분석 부하를 바꾼다. 현재 바인딩 중이면 OFF 경계에서만 use case를
     * 다시 묶고, WARM↔ACTIVE는 analyzer attach/clear만 수행한다.
     */
    fun setAnalysisMode(mode: AnalysisMode) {
        analysisMode = mode
        frameAnalysisAdapter.setEnabled(mode == AnalysisMode.ACTIVE)
        runOnMain(::applyAnalysisMode)
    }

    /** 단순 호출자를 위한 API. false는 기본적으로 ImageAnalysis까지 해제한다. */
    fun setAnalysisEnabled(enabled: Boolean, unbindWhenDisabled: Boolean = true) {
        setAnalysisMode(
            when {
                enabled -> AnalysisMode.ACTIVE
                unbindWhenDisabled -> AnalysisMode.OFF
                else -> AnalysisMode.WARM
            }
        )
    }

    /**
     * 카메라 파이프라인을 시작하고 [previewView] 에 미리보기를 연결한다.
     * CAMERA 권한이 이미 승인된 상태에서 호출해야 한다.
     */
    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) {
        val requestGeneration = synchronized(bindingLock) {
            bindingGeneration++
            boundLifecycleOwner = lifecycleOwner
            boundPreviewView = previewView
            bindingGeneration
        }
        // Compose AndroidView가 아직 attach/layout 전이면 viewPort가 null이다. layout 직후 한 번
        // 재확인해 호출자가 별도 UI wiring을 하지 않아도 세 use case의 crop 기준을 맞춘다.
        previewView.doOnLayout { refreshViewPort() }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val stillRequested = synchronized(bindingLock) {
                    requestGeneration == bindingGeneration &&
                        boundLifecycleOwner === lifecycleOwner && boundPreviewView === previewView
                }
                if (!stillRequested) return@addListener
                cameraProvider = provider
                bindUseCases(provider, lifecycleOwner, previewView)
                onReady()
            } catch (t: Throwable) {
                Log.e(TAG, "카메라 시작 실패", t)
                onError(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
    ) {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        // 순간 포착이 목적이므로 셔터 지연 최소화를 우선한다.
        // targetRotation 은 기기 물리 방향을 따른다 (가로모드) — 이후 변경은 orientationListener 가 반영.
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(deviceRotation)
            .build()
        orientationListener.enable()

        imageAnalysis?.clearAnalyzer()
        analyzerAttached = false
        imageAnalysis = if (analysisMode == AnalysisMode.OFF) {
            null
        } else {
            // 저해상도 분석 스트림은 Preview/원본 촬영과 분리한다. 느리면 최신 프레임만 유지한다.
            // 반드시 4:3 로 고정한다 (2026-08-25): 예전 setTargetResolution(640x480) 은 세로
            // targetRotation 기준으로 해석돼 기기가 1:1 스트림을 고르는 일이 있었고, ViewPort(FIT)가
            // 세 use case 버퍼의 교집합을 공통 크롭으로 삼기 때문에 **사진까지 3060x3060 정사각**으로
            // 잘려 저장됐다. 분석 버퍼가 4:3 이면 교집합 = 센서 전체(4:3)라 사진이 원본 비율로 나온다.
            // targetRotation 은 rotationDegrees 메타데이터만 바꾼다 — ResolutionSelector 는 센서
            // 좌표 기준이라 위 4:3 고정(정사각 사진 회귀)과는 무관하다.
            ImageAnalysis.Builder()
                .setTargetRotation(deviceRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                ANALYSIS_RESOLUTION,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .build()
        }
        frameAnalysisAdapter.setEnabled(analysisMode == AnalysisMode.ACTIVE)
        if (analysisMode == AnalysisMode.ACTIVE) attachAnalyzer()

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        provider.unbindAll()
        val groupBuilder = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(requireNotNull(imageCapture))
        imageAnalysis?.let(groupBuilder::addUseCase)
        val viewPort = previewView.viewPort
        if (viewPort != null) groupBuilder.setViewPort(viewPort)
        boundWithViewPort = viewPort != null
        camera = provider.bindToLifecycle(lifecycleOwner, selector, groupBuilder.build())
        // 사진 정사각 크롭 회귀 감시용 — cropRect 가 resolution 전체와 다르면 ViewPort 교집합이
        // 다시 좁아진 것이다 (위 4:3 고정 주석 참고).
        Log.i(
            TAG,
            "바인딩 완료: capture=${imageCapture?.resolutionInfo?.resolution}" +
                " crop=${imageCapture?.resolutionInfo?.cropRect}" +
                ", analysis=${imageAnalysis?.resolutionInfo?.resolution}",
        )
        // 기본 상태: 연속 AF (CameraX 기본값). 별도 호출 불필요.
    }

    /** PreviewView가 layout된 뒤 공통 crop/view port가 생겼다면 세 use case를 한 번 다시 맞춘다. */
    fun refreshViewPort() {
        runOnMain {
            val provider = cameraProvider ?: return@runOnMain
            val (owner, view) = currentBinding() ?: return@runOnMain
            if (camera != null && !boundWithViewPort && view.viewPort != null) {
                bindUseCases(provider, owner, view)
            }
        }
    }

    /** 전/후면 렌즈 전환. start() 이후에만 유효. */
    fun toggleLens(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        runOnMain {
            val provider = cameraProvider ?: return@runOnMain
            if (camera == null) return@runOnMain
            synchronized(bindingLock) {
                bindingGeneration++
                boundLifecycleOwner = lifecycleOwner
                boundPreviewView = previewView
            }
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            bindUseCases(provider, lifecycleOwner, previewView)
        }
    }

    /**
     * 화면 좌표 (x, y) 지점에 초점·노출을 맞춘다.
     * 시각장애인 UX 특성상 직접 탭보다는, ③ 판정 로직이 "피사체 위치"를 주면
     * 그 좌표로 호출하는 용도를 상정한다. 5초 후 자동으로 연속 AF 로 복귀.
     */
    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    // 현재/최소/최대 줌 배율. 카메라 준비 전엔 1f. 초광각을 논리 카메라로 묶은 기기는 min 이 1 미만(예: 0.6).
    val zoomRatio: Float get() = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
    val minZoomRatio: Float get() = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
    val maxZoomRatio: Float get() = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    /**
     * 줌 배율 적용 (1.0 = 기본 렌즈). 기기 지원 범위 [min, max] 로 클램프된다 —
     * 1 미만(초광각)을 지원하지 않는 기기에서는 0.6 을 넘겨도 1.0 이 된다.
     */
    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value
        val min = state?.minZoomRatio ?: 1f
        val max = state?.maxZoomRatio ?: 1f
        cam.cameraControl.setZoomRatio(ratio.coerceIn(min, max))
    }

    /**
     * 노출 보정. [value] 는 -1.0(어둡게) ~ +1.0(밝게) 비율로 받고
     * 기기가 지원하는 인덱스 범위로 변환해 적용한다.
     */
    fun setExposure(value: Float) {
        val cam = camera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        if (range.lower == 0 && range.upper == 0) return
        val clamped = value.coerceIn(-1f, 1f)
        val index = if (clamped >= 0) {
            (clamped * range.upper).toInt()
        } else {
            (-clamped * range.lower).toInt()
        }
        cam.cameraControl.setExposureCompensationIndex(index)
    }

    /**
     * 사진 촬영. 결과는 MediaStore(Pictures/SnapSight)에 저장되고
     * [captureEventListener] 로 통지된다.
     *
     * [burstCount] 가 2 이상이고 세션 ID가 있으면 연사 모드 — 그 수만큼 연속으로 찍어
     * 가장 선명한 한 장만 MediaStore 에 저장한다 ([BurstPhotoSelector], 2026-08-24).
     * 통지는 일반 촬영과 동일하게 onPhotoSaved 1회라 하위 파이프라인은 차이를 모른다.
     */
    fun takePhoto(sessionId: String? = null, burstCount: Int = 1) {
        val capture = imageCapture ?: run {
            captureEventListener?.onCaptureError(
                sessionId,
                IllegalStateException("카메라가 아직 준비되지 않음"),
            )
            return
        }
        val requestGeneration = captureGeneration.incrementAndGet()

        // 세션 ID를 파일명에 심어 사진 찾기 화면이 AI 설명을 역조회할 수 있게 한다 (#78)
        val name = "SnapSight_" + (sessionId?.takeIf { it.isNotBlank() }
            ?: SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()))
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SnapSight")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues,
        ).build()

        // 이슈 "맞으면 확대된 사진이 안 찍힘" 재현용 — 셔터 시점의 실제 줌 배율을 남긴다.
        Log.i(TAG, "셔터: zoom=%.2f (min %.2f / max %.2f)".format(zoomRatio, minZoomRatio, maxZoomRatio))
        captureEventListener?.onShutter(sessionId)

        // 광각(1.0 미만)은 피사체 찾기용이지 촬영 배율이 아니다. 아직 광각이면 1.0 으로 맞춘 뒤 찍는다.
        val cam = camera
        if (cam != null && zoomRatio < 1f - 0.01f) {
            val zoomFuture = cam.cameraControl.setZoomRatio(1f)
            zoomFuture.addListener({
                if (requestGeneration != captureGeneration.get()) return@addListener
                Log.i(TAG, "셔터 전 줌 1.0 복귀 완료 → 촬영")
                startCapture(capture, outputOptions, sessionId, burstCount, requestGeneration)
            }, ContextCompat.getMainExecutor(context))
            return
        }
        startCapture(capture, outputOptions, sessionId, burstCount, requestGeneration)
    }

    private fun startCapture(
        capture: ImageCapture,
        outputOptions: ImageCapture.OutputFileOptions,
        sessionId: String?,
        burstCount: Int,
        requestGeneration: Long,
    ) {
        if (burstCount > 1 && sessionId != null) {
            captureBurst(capture, sessionId, burstCount, requestGeneration)
        } else {
            doTakePicture(capture, outputOptions, sessionId, requestGeneration)
        }
    }

    /**
     * 연사 촬영 — [count]장을 앱 캐시에 순차 저장하고, 가장 선명한 한 장만 MediaStore 로
     * 옮긴 뒤 나머지를 지운다. MediaStore 를 오염시키지 않아 갤러리·사진 찾기에는 승자
     * 한 장만 보인다. 개별 컷 실패는 건너뛰고, 전부 실패했을 때만 onCaptureError.
     */
    private fun captureBurst(
        capture: ImageCapture,
        sessionId: String,
        count: Int,
        requestGeneration: Long,
    ) {
        val shots = ArrayList<File>(count)

        fun cleanup() = shots.forEach { runCatching { it.delete() } }

        fun finish() {
            if (requestGeneration != captureGeneration.get()) {
                cleanup()
                return
            }
            if (shots.isEmpty()) {
                captureEventListener?.onCaptureError(
                    sessionId,
                    IllegalStateException("연사 촬영이 모두 실패함"),
                )
                return
            }
            // 채점(JPEG 디코드 + 라플라시안)과 MediaStore 복사는 메인 스레드 밖에서
            Thread({
                // 컷별 점수를 전부 남긴다 — `adb logcat -s CameraController` 로 선택 근거를 검증
                val scored = shots.map { it to BurstPhotoSelector.scoreJpeg(it) }
                scored.forEachIndexed { index, (shot, score) ->
                    Log.i(
                        TAG,
                        "연사 채점 [$sessionId] ${index + 1}/${scored.size}: " +
                            "${shot.name} (${shot.length()} bytes) → 선명도 %.1f".format(score),
                    )
                }
                val best = scored.maxByOrNull { it.second }?.first ?: shots.first()
                Log.i(TAG, "연사 최고 컷 선택 [$sessionId]: ${best.name}")
                // 승자 후처리 (인물 3분할 크롭 등) — 실패해도 원본 게시로 이어간다
                val finished = runCatching { burstFinisher?.invoke(best) }
                    .onFailure { Log.w(TAG, "연사 승자 후처리 실패 — 원본 게시", it) }
                    .getOrNull()
                val uri = runCatching { publishBurstWinner(finished ?: best, sessionId) }
                    .onFailure { Log.e(TAG, "연사 최고 컷 저장 실패", it) }
                    .getOrNull()
                preserveBurstShotsForDebug(scored, best, sessionId, finished)
                ContextCompat.getMainExecutor(context).execute {
                    if (requestGeneration != captureGeneration.get()) return@execute
                    if (uri != null) {
                        captureEventListener?.onPhotoSaved(sessionId, uri)
                    } else {
                        captureEventListener?.onCaptureError(
                            sessionId,
                            IllegalStateException("연사 최고 컷을 저장하지 못함"),
                        )
                    }
                }
            }, "SnapSight-BurstSelect-${sessionId.takeLast(8)}").start()
        }

        fun shoot(index: Int) {
            if (requestGeneration != captureGeneration.get()) {
                cleanup()
                return
            }
            if (index >= count) {
                finish()
                return
            }
            val file = File(context.cacheDir, "SnapSight_burst_${sessionId}_$index.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            capture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        shots.add(file)
                        shoot(index + 1)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.w(TAG, "연사 ${index + 1}/$count 촬영 실패 — 나머지로 계속", exception)
                        runCatching { file.delete() }
                        shoot(index + 1)
                    }
                },
            )
        }
        shoot(0)
    }

    /**
     * 연사 디버그 보존 (사용자 요청 2026-08-24) — 탈락 컷 포함 전 컷을 점수·승자 표시가
     * 담긴 이름으로 앱 외부 전용 폴더에 옮긴다. 눈으로 어떤 컷들 중 뭘 골랐는지 검증하는
     * 용도이며, PC 에서 바로 꺼내 볼 수 있다:
     *
     *   adb pull /sdcard/Android/data/<패키지>/files/burst_debug
     *
     * 폴더는 세션 ID 별이고 최근 [BURST_DEBUG_KEEP]개 세션만 남긴다. 보존에 실패하면
     * 원본 캐시 컷을 지워 기존 정리 동작으로 돌아간다.
     */
    private fun preserveBurstShotsForDebug(
        scored: List<Pair<File, Double>>,
        best: File,
        sessionId: String,
        processed: File? = null,
    ) {
        try {
            val root = File(context.getExternalFilesDir(null) ?: context.cacheDir, BURST_DEBUG_DIR)
            val dir = File(root, sessionId)
            dir.mkdirs()
            for ((shot, score) in scored) {
                val marker = if (shot == best) "_WINNER" else ""
                val target = File(dir, "%s_score%.1f%s.jpg".format(shot.nameWithoutExtension, score, marker))
                if (!shot.renameTo(target)) {
                    shot.copyTo(target, overwrite = true)
                    shot.delete()
                }
            }
            // 후처리본(크롭)도 같이 남겨 전/후를 눈으로 비교할 수 있게 한다
            processed?.let { file ->
                val target = File(dir, file.name)
                if (!file.renameTo(target)) {
                    file.copyTo(target, overwrite = true)
                    file.delete()
                }
            }
            Log.i(TAG, "연사 컷 보존 [$sessionId]: ${dir.absolutePath}")
            // 오래된 세션 폴더 정리 — 디버그 보존이 저장 공간을 무한정 먹지 않게
            root.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(BURST_DEBUG_KEEP)
                ?.forEach { it.deleteRecursively() }
        } catch (t: Throwable) {
            Log.w(TAG, "연사 디버그 보존 실패 — 캐시 컷 삭제로 대체", t)
            scored.forEach { (shot, _) -> runCatching { shot.delete() } }
            processed?.let { runCatching { it.delete() } }
        }
    }

    /**
     * 연사 승자를 일반 촬영과 같은 이름(SnapSight_<sessionId>)·경로로 MediaStore 에
     * 게시한다. [CanonicalFrameStore] 와 같은 IS_PENDING 패턴 — 쓰다 만 파일이
     * 갤러리에 노출되지 않는다.
     */
    private fun publishBurstWinner(file: File, sessionId: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SnapSight_$sessionId")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SnapSight")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        ) { "MediaStore insert returned null" }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: error("MediaStore output stream could not be opened")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /** 아직 CameraX에 전달되지 않은 줌 대기 촬영을 무효화한다. 저장 중 요청은 콜백에서 세션 ID로 걸러진다. */
    fun cancelPendingCapture() {
        captureGeneration.incrementAndGet()
    }

    private fun doTakePicture(
        capture: ImageCapture,
        outputOptions: ImageCapture.OutputFileOptions,
        sessionId: String?,
        requestGeneration: Long,
    ) {
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (requestGeneration != captureGeneration.get()) return
                    val uri = output.savedUri
                    if (uri != null) {
                        captureEventListener?.onPhotoSaved(sessionId, uri)
                    } else {
                        captureEventListener?.onCaptureError(
                            sessionId,
                            IllegalStateException("저장은 됐지만 Uri 를 받지 못함")
                        )
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (requestGeneration != captureGeneration.get()) return
                    Log.e(TAG, "촬영 실패", exception)
                    captureEventListener?.onCaptureError(sessionId, exception)
                }
            },
        )
    }

    /**
     * 카메라 사용 중지 (화면 이탈 시 — CaptureScreen 의 onDispose). **일시 중지 의미**라
     * CV 프로세서·링 버퍼 연결과 분석 스레드는 그대로 둔다 — 다음 [start] 가 카메라만 다시 묶으면
     * 분석이 이어진다.
     *
     * 예전엔 여기서 프로세서를 떼고 분석 executor 까지 종료했는데, [start] 는 프로세서를 다시
     * 붙이지 않으므로 설정/사진 찾기 화면을 다녀오면 미리보기만 살고 **탐지·링 버퍼가 영구히
     * 죽는** 버그가 있었다 (2026-08-22 사물 등록 "찾지 못했어요" 원인). 완전 해제는 [release].
     */
    fun shutdown() {
        synchronized(bindingLock) {
            bindingGeneration++
            boundLifecycleOwner = null
            boundPreviewView = null
        }
        orientationListener.disable()
        cancelPendingCapture()
        imageAnalysis?.clearAnalyzer()
        analyzerAttached = false
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        imageAnalysis = null
        boundWithViewPort = false
    }

    /** 완전 해제 — Activity 종료 시. 프로세서·링 버퍼를 떼고 분석 스레드를 종료한다. */
    fun release() {
        shutdown()
        frameAnalysisAdapter.setProcessor(null)
        frameAnalysisAdapter.setSink(null)
        analysisExecutor.shutdown()
    }

    private fun applyAnalysisMode() {
        val provider = cameraProvider ?: return
        val (owner, view) = currentBinding() ?: return
        if (camera == null) return

        val shouldBindAnalysis = analysisMode != AnalysisMode.OFF
        if ((imageAnalysis != null) != shouldBindAnalysis) {
            bindUseCases(provider, owner, view)
            return
        }
        frameAnalysisAdapter.setEnabled(analysisMode == AnalysisMode.ACTIVE)
        if (analysisMode == AnalysisMode.ACTIVE) attachAnalyzer() else detachAnalyzer()
    }

    private fun attachAnalyzer() {
        val analysis = imageAnalysis ?: return
        if (analyzerAttached) return
        if (analysisExecutor.isShutdown) analysisExecutor = Executors.newSingleThreadExecutor()
        analysis.setAnalyzer(analysisExecutor, frameAnalysisAdapter)
        analyzerAttached = true
    }

    private fun detachAnalyzer() {
        if (!analyzerAttached) return
        imageAnalysis?.clearAnalyzer()
        analyzerAttached = false
    }

    private fun currentBinding(): Pair<LifecycleOwner, PreviewView>? = synchronized(bindingLock) {
        val owner = boundLifecycleOwner ?: return@synchronized null
        val view = boundPreviewView ?: return@synchronized null
        owner to view
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else ContextCompat.getMainExecutor(context).execute(action)
    }

    private companion object {
        const val TAG = "CameraController"

        /** 연사 디버그 보존 폴더 (외부 앱 전용 저장소) — adb pull 로 접근 가능. */
        const val BURST_DEBUG_DIR = "burst_debug"

        /** 보존할 최근 세션 폴더 수 — 초과분은 오래된 것부터 삭제. */
        const val BURST_DEBUG_KEEP = 20
        /** 분석 스트림 목표 크기 — 센서(가로) 좌표 기준. AspectRatioStrategy 4:3 과 짝. */
        val ANALYSIS_RESOLUTION = Size(640, 480)
    }
}
