// 이 파일: 카메라를 켜고 끄고 사진을 찍는 심장부.
// 미리보기 연결, 초점·노출 조절, 촬영 요청까지 카메라 관련 일을 전부 여기서 처리한다.
// 다른 모듈은 카메라를 직접 만지지 않고 이 클래스만 부르면 된다.
package com.example.snap_sight.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import com.example.snap_sight.cv.FrameProcessor
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

    // release() 이후 다시 start() 되는 경우에 대비해 재생성 가능하게 var 로 둔다
    private var analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameAnalysisAdapter = FrameAnalysisAdapter()

    /** 촬영 이벤트 수신자. 언제든 교체 가능. */
    var captureEventListener: CaptureEventListener? = null

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
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        imageAnalysis?.clearAnalyzer()
        analyzerAttached = false
        imageAnalysis = if (analysisMode == AnalysisMode.OFF) {
            null
        } else {
            // 저해상도 분석 스트림은 Preview/원본 촬영과 분리한다. 느리면 최신 프레임만 유지한다.
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(ANALYSIS_RESOLUTION)
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
     */
    fun takePhoto(sessionId: String? = null) {
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
                doTakePicture(capture, outputOptions, sessionId, requestGeneration)
            }, ContextCompat.getMainExecutor(context))
            return
        }
        doTakePicture(capture, outputOptions, sessionId, requestGeneration)
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
        val ANALYSIS_RESOLUTION = Size(640, 480)
    }
}
