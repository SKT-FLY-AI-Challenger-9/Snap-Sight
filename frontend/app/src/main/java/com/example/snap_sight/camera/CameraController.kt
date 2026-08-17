// 이 파일: 카메라를 켜고 끄고 사진을 찍는 심장부.
// 미리보기 연결, 초점·노출 조절, 촬영 요청까지 카메라 관련 일을 전부 여기서 처리한다.
// 다른 모듈은 카메라를 직접 만지지 않고 이 클래스만 부르면 된다.
package com.example.snap_sight.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.snap_sight.cv.FrameProcessor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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
     * 카메라 파이프라인을 시작하고 [previewView] 에 미리보기를 연결한다.
     * CAMERA 권한이 이미 승인된 상태에서 호출해야 한다.
     */
    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
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

        // ② CV 추론 + 링 버퍼용 프레임 스트림. 소비가 느리면 최신 프레임만 유지.
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, frameAnalysisAdapter) }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        provider.unbindAll()
        camera = provider.bindToLifecycle(
            lifecycleOwner, selector, preview, imageCapture, imageAnalysis
        )
        // 기본 상태: 연속 AF (CameraX 기본값). 별도 호출 불필요.
    }

    /** 전/후면 렌즈 전환. start() 이후에만 유효. */
    fun toggleLens(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindUseCases(provider, lifecycleOwner, previewView)
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
    fun takePhoto() {
        val capture = imageCapture ?: run {
            captureEventListener?.onCaptureError(IllegalStateException("카메라가 아직 준비되지 않음"))
            return
        }

        val name = "SnapSight_" +
                SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
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

        captureEventListener?.onShutter()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri
                    if (uri != null) {
                        captureEventListener?.onPhotoSaved(uri)
                    } else {
                        captureEventListener?.onCaptureError(
                            IllegalStateException("저장은 됐지만 Uri 를 받지 못함")
                        )
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "촬영 실패", exception)
                    captureEventListener?.onCaptureError(exception)
                }
            },
        )
    }

    /** 카메라 해제. Activity/화면 종료 시 호출. */
    fun shutdown() {
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        frameAnalysisAdapter.setProcessor(null)
        frameAnalysisAdapter.setSink(null)
        analysisExecutor.shutdown()
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
