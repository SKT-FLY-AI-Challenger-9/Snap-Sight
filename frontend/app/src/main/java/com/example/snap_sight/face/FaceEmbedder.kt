// 이 파일: 얼굴 크롭 → 임베딩 벡터 변환 (기능 2). TFLite 에 의존하는 유일한 얼굴 모듈 파일 —
// TfLiteYoloDetector 와 같은 격리 원칙이다. 모델 자산이 없어도 앱은 정상 동작한다
// (isAvailable=false → 인물 인식만 꺼진 상태).
package com.example.snap_sight.face

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

interface FaceEmbedder {
    /** 모델이 준비돼 있는지. false 면 [embed] 는 항상 null. */
    val isAvailable: Boolean

    /** 정면 정렬된 얼굴 크롭 → L2 정규화된 임베딩. 실패 시 null. */
    fun embed(face: Bitmap): FloatArray?

    fun close()
}

/**
 * MobileFaceNet 계열 .tflite 임베더.
 *
 * 모델 자산 준비 (assets/README.md 참고):
 *  - assets/[MODEL_ASSET] 에 112×112 RGB 입력, float 임베딩 출력 모델을 배치한다
 *  - 입력 정규화는 (pixel − 127.5) / 128 — MobileFaceNet 표준 전처리
 *
 * 로드는 첫 [embed] 호출(분석 스레드)에서 지연 수행한다.
 */
class TfLiteFaceEmbedder(
    private val context: Context,
    private val modelAsset: String = MODEL_ASSET,
) : FaceEmbedder {

    private var interpreter: Interpreter? = null
    private var loadFailed = false
    private var inputSize = DEFAULT_INPUT_SIZE
    private var outputDim = 0

    override val isAvailable: Boolean
        get() = !loadFailed && (interpreter != null || assetExists())

    override fun embed(face: Bitmap): FloatArray? {
        val runtime = ensureLoaded() ?: return null
        return try {
            val scaled = if (face.width == inputSize && face.height == inputSize) face
            else Bitmap.createScaledBitmap(face, inputSize, inputSize, true)

            val input = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
                .order(ByteOrder.nativeOrder())
            val pixels = IntArray(inputSize * inputSize)
            scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
            for (pixel in pixels) {
                input.putFloat(((pixel shr 16 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((pixel shr 8 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((pixel and 0xFF) - 127.5f) / 128f)
            }
            input.rewind()

            val output = Array(1) { FloatArray(outputDim) }
            runtime.run(input, output)
            l2Normalize(output[0])
        } catch (t: Throwable) {
            Log.w(TAG, "얼굴 임베딩 실패", t)
            null
        }
    }

    override fun close() {
        runCatching { interpreter?.close() }
        interpreter = null
    }

    private fun ensureLoaded(): Interpreter? {
        interpreter?.let { return it }
        if (loadFailed) return null
        return try {
            val buffer = context.assets.openFd(modelAsset).use { descriptor ->
                descriptor.createInputStream().channel.map(
                    FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength,
                )
            }
            val created = Interpreter(buffer, Interpreter.Options().apply { numThreads = 2 })
            val inputShape = created.getInputTensor(0).shape() // [1, H, W, 3]
            inputSize = inputShape.getOrNull(1)?.takeIf { it > 0 } ?: DEFAULT_INPUT_SIZE
            outputDim = created.getOutputTensor(0).shape().last()
            interpreter = created
            Log.i(TAG, "얼굴 임베더 로드 완료 — 입력 ${inputSize}px, 임베딩 ${outputDim}차원")
            created
        } catch (t: Throwable) {
            loadFailed = true
            Log.w(TAG, "얼굴 임베더 없음(assets/$modelAsset) — 인물 인식 기능 꺼짐", t)
            null
        }
    }

    private fun assetExists(): Boolean = try {
        context.assets.openFd(modelAsset).use { true }
    } catch (t: Throwable) {
        loadFailed = true
        false
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var norm = 0f
        for (value in vector) norm += value * value
        if (norm <= 0f) return vector
        val inverse = 1f / sqrt(norm)
        return FloatArray(vector.size) { vector[it] * inverse }
    }

    companion object {
        private const val TAG = "TfLiteFaceEmbedder"
        const val MODEL_ASSET = "face_embedder.tflite"
        private const val DEFAULT_INPUT_SIZE = 112
    }
}
