// 이 파일: 사물 크롭 → 심층 특징 벡터 (2026-08-23) — MobileNetV3-Small .tflite 임베더.
// 수제 히스토그램 임베더(LocalObjectAppearanceEmbedder)가 봉제인형처럼 질감이 비슷한
// 물건들에서 유사도 0.9+ 포화로 구분 불가함이 실기기 로그로 확인돼 교체한다.
// TfLiteFaceEmbedder 와 같은 격리·지연 로드·자산 없으면 폴백 원칙. DB 는 modelId 로
// 분리돼 있어 이전 임베딩과 섞이지 않는다 (사용자는 재등록만 하면 됨).
package com.example.snap_sight.face

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * MobileNetV3-Small 특징 추출기 (ai/tools/export_object_embedder.py 로 생성).
 *  - 입력: 224×224 RGB, 픽셀값 0..255 float (전처리 모델 내장)
 *  - 출력: 576차원 특징 → L2 정규화 후 cosine 비교
 */
class TfLiteObjectEmbedder(
    private val context: Context,
    private val modelAsset: String = MODEL_ASSET,
) : ObjectEmbedder {

    private var interpreter: Interpreter? = null
    private var loadFailed = false
    private var inputSize = DEFAULT_INPUT_SIZE
    private var outputDim = 0
    private var inputBuffer: ByteBuffer? = null
    private var pixelBuffer: IntArray? = null
    private var outputBuffer: Array<FloatArray>? = null

    override val modelId: String = MODEL_ID

    override val isAvailable: Boolean
        get() = !loadFailed && (interpreter != null || assetExists())

    override fun embed(objectCrop: Bitmap): FloatArray? {
        if (objectCrop.width <= 0 || objectCrop.height <= 0) return null
        val runtime = ensureLoaded() ?: return null
        val scaled = if (objectCrop.width == inputSize && objectCrop.height == inputSize) {
            objectCrop
        } else {
            Bitmap.createScaledBitmap(objectCrop, inputSize, inputSize, true)
        }
        return try {
            val input = inputBuffer ?: ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
                .order(ByteOrder.nativeOrder()).also { inputBuffer = it }
            val pixels = pixelBuffer ?: IntArray(inputSize * inputSize).also { pixelBuffer = it }
            input.clear()
            scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
            for (pixel in pixels) {
                // 전처리(스케일링)는 모델에 내장 — 0..255 값을 그대로 넣는다
                input.putFloat((pixel shr 16 and 0xFF).toFloat())
                input.putFloat((pixel shr 8 and 0xFF).toFloat())
                input.putFloat((pixel and 0xFF).toFloat())
            }
            input.rewind()

            val output = outputBuffer ?: Array(1) { FloatArray(outputDim) }.also { outputBuffer = it }
            runtime.run(input, output)
            l2Normalize(output[0].copyOf())
        } catch (t: Throwable) {
            Log.w(TAG, "사물 임베딩 실패", t)
            null
        } finally {
            if (scaled !== objectCrop) scaled.recycle()
        }
    }

    override fun close() {
        runCatching { interpreter?.close() }
        interpreter = null
        inputBuffer = null
        pixelBuffer = null
        outputBuffer = null
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
            Log.i(TAG, "사물 임베더 로드 완료 — 입력 ${inputSize}px, 특징 ${outputDim}차원")
            created
        } catch (t: Throwable) {
            loadFailed = true
            Log.w(TAG, "사물 임베더 없음(assets/$modelAsset) — 수제 외형 임베더로 폴백", t)
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
        for (index in vector.indices) vector[index] *= inverse
        return vector
    }

    private companion object {
        const val TAG = "TfLiteObjectEmbedder"
        const val MODEL_ASSET = "object_embedder.tflite"
        /** DB 분리 키 — 수제 임베더("local_appearance_v1" 계열)와 절대 겹치면 안 된다. */
        const val MODEL_ID = "mobilenetv3s_224_v1"
        const val DEFAULT_INPUT_SIZE = 224
    }
}
