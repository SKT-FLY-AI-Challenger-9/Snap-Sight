package com.example.snap_sight.cv

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Objects365 YOLO 체크포인트의 TFLite adapter.
 *
 * **이 저장소에서 TFLite API 에 의존하는 유일한 파일이다.** 여기가 맡는 일은 런타임에
 * 종속적인 것뿐이다: 모델 로드, letterbox 입력 작성, quantize/dequantize, 추론 실행.
 * 출력 해석은 런타임과 무관한 [YoloOutputDecoder] 가 담당하므로 단위 테스트로 검증된다.
 *
 * 모델 자산은 `ai/tools/export_tflite.py` 로 만든다. assets 에 없으면 [load] 가
 * [ModelUnavailableException] 을 던지고, [SnapSightFrameProcessor] 가 이를 잡아
 * "검출 0개" 로 계속 동작한다.
 */
data class TfLiteDetectorConfig(
    val modelAsset: String = "objects365_yolo26_v1.tflite",
    val labelsAsset: String = "objects365_yolo26_v1_labels.txt",
    /**
     * detector 가 내보낼 최소 confidence.
     * tracker 의 `minimumMatchingConfidence` 와 맞춘다 — 저신뢰 검출은 공개 결과에는 안 나오지만
     * 기존 track 의 ID 복구에 쓰이므로 여기서 미리 잘라내면 안 된다.
     */
    val minimumConfidence: Float = 0.10f,
    val nmsIouThreshold: Float = 0.45f,
    val maxDetections: Int = 300,
    val numThreads: Int = 4,
    /**
     * class 별 NMS 적용 여부. `[1, N, 6]` end-to-end(NMS-free) 출력에서는 모델이 이미
     * 중복을 제거했으므로 이 값과 무관하게 건너뛴다.
     */
    val applyNms: Boolean = true,
) {
    init {
        require(minimumConfidence in 0f..1f) { "minimumConfidence must be in [0, 1]" }
        require(nmsIouThreshold in 0f..1f) { "nmsIouThreshold must be in [0, 1]" }
        require(maxDetections > 0) { "maxDetections must be positive" }
        require(numThreads > 0) { "numThreads must be positive" }
    }
}

class TfLiteYoloDetector(
    private val context: Context,
    private val config: TfLiteDetectorConfig = TfLiteDetectorConfig(),
) : Detector {

    private var interpreter: Interpreter? = null
    private var decoder: YoloOutputDecoder? = null
    private var labels: List<String> = emptyList()

    private var inputSpec: InputTensorSpec? = null
    private var inputDataType = DataType.FLOAT32
    private var inputQuantScale = 1f
    private var inputQuantZeroPoint = 0

    private var outputDataType = DataType.FLOAT32
    private var outputQuantScale = 1f
    private var outputQuantZeroPoint = 0
    private var outputElementCount = 0

    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private var outputValues = FloatArray(0)

    override fun load() {
        if (interpreter != null) return

        labels = readLabels()
        if (labels.isEmpty()) {
            throw ModelUnavailableException("라벨 파일이 비어 있음: assets/${config.labelsAsset}")
        }

        val options = Interpreter.Options().apply { setNumThreads(config.numThreads) }
        val created = try {
            Interpreter(readModelBuffer(), options)
        } catch (t: Throwable) {
            throw ModelUnavailableException("TFLite 인터프리터 생성 실패: ${config.modelAsset}", t)
        }

        try {
            val inputTensor = created.getInputTensor(0)
            val inputShape = inputTensor.shape()
            val spec = InputTensorSpec.of(inputShape)
                ?: throw ModelUnavailableException(
                    "입력 텐서는 [1, H, W, 3] 또는 [1, 3, H, W] 여야 함: ${inputShape.joinToString()}"
                )
            inputSpec = spec
            inputDataType = inputTensor.dataType()
            inputTensor.quantizationParams().let {
                inputQuantScale = if (it.scale != 0f) it.scale else 1f
                inputQuantZeroPoint = it.zeroPoint
            }

            val outputTensor = created.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            outputDataType = outputTensor.dataType()
            outputTensor.quantizationParams().let {
                outputQuantScale = if (it.scale != 0f) it.scale else 1f
                outputQuantZeroPoint = it.zeroPoint
            }

            val layout = YoloOutputDecoder.layoutFor(outputShape, labels.size)
                ?: throw ModelUnavailableException(
                    "해석할 수 없는 출력 형태 ${outputShape.joinToString()} — " +
                            "라벨 ${labels.size}개 기준으로 4+nc=${4 + labels.size} 또는 6 을 기대함. " +
                            "ai/tools/export_tflite.py 가 출력한 shape 와 라벨 파일을 확인할 것."
                )
            val anchorCount: Int
            val channelCount: Int
            if (layout == YoloOutputLayout.CHANNELS_FIRST) {
                channelCount = outputShape[1]
                anchorCount = outputShape[2]
            } else {
                anchorCount = outputShape[1]
                channelCount = outputShape[2]
            }
            outputElementCount = anchorCount * channelCount

            decoder = YoloOutputDecoder(
                labels = labels,
                layout = layout,
                anchorCount = anchorCount,
                channelCount = channelCount,
                minimumConfidence = config.minimumConfidence,
                nmsIouThreshold = config.nmsIouThreshold,
                maxDetections = config.maxDetections,
                applyNms = config.applyNms,
            )

            inputBuffer = ByteBuffer
                .allocateDirect(spec.pixelCount * 3 * inputDataType.byteSize())
                .order(ByteOrder.nativeOrder())
            outputBuffer = ByteBuffer
                .allocateDirect(outputElementCount * outputDataType.byteSize())
                .order(ByteOrder.nativeOrder())
            outputValues = FloatArray(outputElementCount)

            interpreter = created
            Log.i(
                TAG,
                "TFLite 로드 완료: 입력 ${inputShape.joinToString("x")}/$inputDataType " +
                        "(${spec.layout} ${spec.width}x${spec.height}), " +
                        "출력 ${outputShape.joinToString("x")}/$outputDataType, " +
                        "layout=$layout, classes=${labels.size}",
            )
        } catch (t: Throwable) {
            created.close()
            throw t
        }
    }

    override fun detect(frame: CvFrame): List<Detection> {
        val activeInterpreter = interpreter ?: throw IllegalStateException("load() 가 먼저 호출돼야 함")
        val activeDecoder = decoder ?: return emptyList()
        val input = inputBuffer ?: return emptyList()
        val output = outputBuffer ?: return emptyList()

        val spec = inputSpec ?: return emptyList()
        val geometry = LetterboxGeometry.of(frame.width, frame.height, spec.width, spec.height)
        writeLetterboxedInput(frame, input, spec, geometry)

        output.rewind()
        activeInterpreter.run(input, output)

        readOutputValues(output)
        val detections = activeDecoder.decode(outputValues, geometry)
        if (!loggedCoordinateUnits) {
            activeDecoder.coordinatesArePixels?.let {
                loggedCoordinateUnits = true
                Log.i(TAG, "박스 좌표 단위: ${if (it) "입력 픽셀" else "정규화 [0,1]"}")
            }
        }
        return detections
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
        decoder = null
        inputSpec = null
        inputBuffer = null
        outputBuffer = null
        outputValues = FloatArray(0)
        loggedCoordinateUnits = false
    }

    private var loggedCoordinateUnits = false

    // -----------------------------------------------------------------------
    // 입력 준비
    // -----------------------------------------------------------------------

    /**
     * 종횡비를 유지해 축소하고 남는 영역은 회색(114)으로 채운다.
     *
     * NCHW 는 채널이 평면으로 떨어져 있어 픽셀당 3개를 연속으로 쓸 수 없다.
     * 두 레이아웃을 한 번의 순회로 처리하려고 절대 인덱스 put 을 쓴다
     * (position 을 건드리지 않으므로 호출 전후로 rewind 한 번이면 충분).
     */
    private fun writeLetterboxedInput(
        frame: CvFrame,
        input: ByteBuffer,
        spec: InputTensorSpec,
        geometry: LetterboxGeometry,
    ) {
        input.rewind()
        val isFloat = inputDataType == DataType.FLOAT32
        val padValue = LETTERBOX_PAD / 255f
        val padX = geometry.padX
        val padY = geometry.padY
        val scaledWidth = geometry.scaledWidth
        val scaledHeight = geometry.scaledHeight

        for (row in 0 until spec.height) {
            val sourceRow = if (row < padY || row >= padY + scaledHeight) {
                -1
            } else {
                ((row - padY) * frame.height / scaledHeight).coerceIn(0, frame.height - 1)
            }
            val rowOffset = row * spec.width
            for (column in 0 until spec.width) {
                val pixelIndex = rowOffset + column
                if (sourceRow < 0 || column < padX || column >= padX + scaledWidth) {
                    writePixel(input, spec, pixelIndex, isFloat, padValue, padValue, padValue)
                    continue
                }
                val sourceColumn =
                    ((column - padX) * frame.width / scaledWidth).coerceIn(0, frame.width - 1)
                val offset = frame.offsetOf(sourceColumn, sourceRow)
                writePixel(
                    input,
                    spec,
                    pixelIndex,
                    isFloat,
                    (frame.rgb[offset].toInt() and 0xFF) / 255f,
                    (frame.rgb[offset + 1].toInt() and 0xFF) / 255f,
                    (frame.rgb[offset + 2].toInt() and 0xFF) / 255f,
                )
            }
        }
        input.rewind()
    }

    private fun writePixel(
        input: ByteBuffer,
        spec: InputTensorSpec,
        pixelIndex: Int,
        isFloat: Boolean,
        red: Float,
        green: Float,
        blue: Float,
    ) {
        if (isFloat) {
            input.putFloat(spec.indexOf(pixelIndex, 0) * Float.SIZE_BYTES, red)
            input.putFloat(spec.indexOf(pixelIndex, 1) * Float.SIZE_BYTES, green)
            input.putFloat(spec.indexOf(pixelIndex, 2) * Float.SIZE_BYTES, blue)
        } else {
            input.put(spec.indexOf(pixelIndex, 0), quantize(red))
            input.put(spec.indexOf(pixelIndex, 1), quantize(green))
            input.put(spec.indexOf(pixelIndex, 2), quantize(blue))
        }
    }

    private fun quantize(normalized: Float): Byte {
        val quantized = Math.round(normalized / inputQuantScale) + inputQuantZeroPoint
        return if (inputDataType == DataType.INT8) {
            quantized.coerceIn(-128, 127).toByte()
        } else {
            quantized.coerceIn(0, 255).toByte()
        }
    }

    // -----------------------------------------------------------------------
    // 출력 읽기 (dequantize)
    // -----------------------------------------------------------------------

    private fun readOutputValues(output: ByteBuffer) {
        output.rewind()
        when (outputDataType) {
            DataType.FLOAT32 -> output.asFloatBuffer().get(outputValues, 0, outputElementCount)
            DataType.INT8 -> for (index in 0 until outputElementCount) {
                outputValues[index] =
                    (output.get(index).toInt() - outputQuantZeroPoint) * outputQuantScale
            }
            else -> for (index in 0 until outputElementCount) {
                outputValues[index] =
                    ((output.get(index).toInt() and 0xFF) - outputQuantZeroPoint) * outputQuantScale
            }
        }
    }

    // -----------------------------------------------------------------------
    // 자산 로딩
    // -----------------------------------------------------------------------

    private fun readModelBuffer(): ByteBuffer {
        val descriptor = try {
            context.assets.openFd(config.modelAsset)
        } catch (e: IOException) {
            throw ModelUnavailableException(
                "모델 자산을 찾을 수 없음: assets/${config.modelAsset}. " +
                        "`python -m ai.tools.export_tflite` 로 생성해 배치할 것.",
                e,
            )
        }
        descriptor.use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength,
                )
            }
        }
    }

    /** 줄 번호가 곧 class ID 다. 빈 줄을 걸러내면 ID 가 밀리므로 마지막 개행만 제거한다. */
    private fun readLabels(): List<String> {
        val raw = try {
            context.assets.open(config.labelsAsset).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: IOException) {
            throw ModelUnavailableException(
                "라벨 자산을 찾을 수 없음: assets/${config.labelsAsset}",
                e,
            )
        }
        return raw.split('\n').map { it.trim('\r', ' ') }.dropLastWhile { it.isEmpty() }
    }

    private companion object {
        const val TAG = "SnapSightCV"
        const val LETTERBOX_PAD = 114
    }
}
