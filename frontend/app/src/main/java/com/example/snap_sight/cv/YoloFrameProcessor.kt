package com.example.snap_sight.cv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.snap_sight.camera.toJpegBytes
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * YOLO26n(TFLite) 온디바이스 객체 탐지 [FrameProcessor].
 *
 * 모델 파일: `app/src/main/assets/models/yolo26n.tflite`
 *  - 레포에는 포함하지 않는다 (용량·라이선스). 준비 방법은 docs/detection-module.md 참고.
 *  - 파일이 없으면 [createIfAvailable] 이 null 을 돌려주고, 호출부는
 *    LoggingFrameProcessor 로 폴백한다 → 모델 없이도 앱은 정상 동작.
 *
 * 추론은 분석 스레드에서 동기로 수행한다. KEEP_ONLY_LATEST 백프레셔가
 * 밀린 프레임을 자동으로 버리므로 추론이 느려도 세션 흐름은 막히지 않는다.
 *
 * Logcat 필터: tag:SnapSightYolo
 */
class YoloFrameProcessor private constructor(
    private val context: Context,
) : FrameProcessor {

    /** 탐지 결과 수신 계약. 분석 스레드에서 호출되므로 UI 접근 시 스레드 전환 필요. */
    interface DetectionListener {
        fun onDetections(detections: List<Detection>, inferenceMs: Long)
    }

    @Volatile
    var listener: DetectionListener? = null

    private var interpreter: Interpreter? = null
    private var inputSize = 0
    private var inputBuffer: ByteBuffer? = null
    private var inputBitmap: Bitmap? = null
    private var outputRows: Array<FloatArray> = emptyArray()

    // fps/지연 측정 (1초 창)
    private var frameCount = 0
    private var inferenceMsSum = 0L
    private var windowStartMs = 0L

    override fun onAttached() {
        try {
            val model = loadMappedModel()
            val itp = Interpreter(model, Interpreter.Options().apply { numThreads = NUM_THREADS })

            val inShape = itp.getInputTensor(0).shape() // [1, H, W, 3]
            val outShape = itp.getOutputTensor(0).shape() // end-to-end: [1, N, 6]
            require(inShape.size == 4 && inShape[1] == inShape[2] && inShape[3] == 3) {
                "지원하지 않는 입력 형태: ${inShape.contentToString()}"
            }
            require(outShape.size == 3 && outShape[2] == 6) {
                "지원하지 않는 출력 형태: ${outShape.contentToString()} " +
                    "(YOLO26 end-to-end [1,N,6] 만 지원 — nms=True 로 내보냈는지 확인)"
            }

            inputSize = inShape[1]
            inputBuffer = ByteBuffer
                .allocateDirect(inputSize * inputSize * 3 * 4)
                .order(ByteOrder.nativeOrder())
            inputBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            outputRows = Array(outShape[1]) { FloatArray(6) }
            interpreter = itp

            windowStartMs = System.currentTimeMillis()
            frameCount = 0
            inferenceMsSum = 0L
            Log.i(TAG, "YOLO26n 로드 완료: 입력 ${inputSize}x$inputSize, 최대 ${outShape[1]}개 탐지")
        } catch (e: Exception) {
            Log.e(TAG, "모델 로드 실패 — 탐지 비활성", e)
            release()
        }
    }

    override fun onFrame(image: ImageProxy, rotationDegrees: Int) {
        val itp = interpreter ?: return
        val started = System.currentTimeMillis()

        // 1) YUV → 정방향 Bitmap (기존 링 버퍼용 변환 유틸 재사용)
        val jpeg = image.toJpegBytes(PREPROCESS_JPEG_QUALITY)
        var bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        if (rotationDegrees != 0) {
            val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }

        // 2) letterbox → float 입력 버퍼
        val srcWidth = bitmap.width
        val srcHeight = bitmap.height
        fillInput(bitmap)

        // 3) 추론 + 후처리
        val output = arrayOf(outputRows)
        itp.run(inputBuffer, output)
        val detections = YoloPostprocessor.decode(
            rows = outputRows,
            inputSize = inputSize,
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            scoreThreshold = SCORE_THRESHOLD,
        )

        val elapsed = System.currentTimeMillis() - started
        listener?.onDetections(detections, elapsed)
        logWindow(elapsed, detections)
    }

    override fun onDetached() {
        release()
        Log.i(TAG, "탐지 파이프라인 분리됨")
    }

    /** 원본을 비율 유지로 축소해 회색(114) 정사각 캔버스 중앙에 놓고 RGB float 로 푼다. */
    private fun fillInput(src: Bitmap) {
        val canvasBitmap = inputBitmap ?: return
        val buffer = inputBuffer ?: return
        val lb = YoloPostprocessor.letterboxFor(src.width, src.height, inputSize)

        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val m = Matrix().apply {
            postScale(lb.scale, lb.scale)
            postTranslate(lb.padX, lb.padY)
        }
        canvas.drawBitmap(src, m, null)

        val pixels = IntArray(inputSize * inputSize)
        canvasBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        buffer.rewind()
        for (p in pixels) {
            buffer.putFloat(((p shr 16) and 0xFF) / 255f)
            buffer.putFloat(((p shr 8) and 0xFF) / 255f)
            buffer.putFloat((p and 0xFF) / 255f)
        }
        buffer.rewind()
    }

    private fun logWindow(inferenceMs: Long, detections: List<Detection>) {
        frameCount++
        inferenceMsSum += inferenceMs
        val now = System.currentTimeMillis()
        val elapsed = now - windowStartMs
        if (elapsed >= 1000) {
            val fps = frameCount * 1000f / elapsed
            val avgMs = inferenceMsSum / frameCount
            val top = detections.firstOrNull()
            Log.d(TAG, "탐지 스트림: %.1f fps, 평균 %dms, 최근 %d개%s".format(
                fps, avgMs, detections.size,
                top?.let { " (top: ${it.label} %.2f)".format(it.score) } ?: ""))
            frameCount = 0
            inferenceMsSum = 0L
            windowStartMs = now
        }
    }

    private fun loadMappedModel(): ByteBuffer =
        context.assets.openFd(MODEL_ASSET_PATH).use { fd ->
            fd.createInputStream().channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }

    private fun release() {
        interpreter?.close()
        interpreter = null
        inputBitmap?.recycle()
        inputBitmap = null
        inputBuffer = null
        outputRows = emptyArray()
    }

    companion object {
        private const val TAG = "SnapSightYolo"
        private const val MODEL_ASSET_PATH = "models/yolo26n.tflite"
        private const val SCORE_THRESHOLD = 0.35f
        private const val NUM_THREADS = 4

        // 전처리 중간 JPEG 품질. 화질보다 속도 우선 (탐지 입력은 어차피 320px 로 축소됨)
        private const val PREPROCESS_JPEG_QUALITY = 80

        /** 모델 에셋이 존재할 때만 인스턴스를 만든다. 없으면 null (호출부에서 폴백). */
        fun createIfAvailable(context: Context): YoloFrameProcessor? = try {
            context.assets.open(MODEL_ASSET_PATH).close()
            YoloFrameProcessor(context.applicationContext)
        } catch (e: Exception) {
            Log.i(TAG, "모델 에셋 없음($MODEL_ASSET_PATH) — 탐지 없이 동작")
            null
        }
    }
}
