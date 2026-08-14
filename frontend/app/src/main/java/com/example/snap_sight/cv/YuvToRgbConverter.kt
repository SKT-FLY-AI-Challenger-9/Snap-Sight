package com.example.snap_sight.cv

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy

/**
 * CameraX 분석 프레임(YUV_420_888) → 회전 보정된 upright RGB888 [CvFrame].
 *
 * 한 번의 순회로 **색 변환 + 회전 + (선택) 다운샘플**을 모두 처리한다.
 * 회전을 별도 패스로 돌리면 프레임마다 버퍼를 한 번 더 훑게 되므로 분석 스레드에서 낭비다.
 *
 * 스레딩: 인스턴스는 스레드 안전하지 않다. CameraX 분석 스레드 하나에서만 쓴다.
 * 반환된 [CvFrame.rgb] 는 다음 호출에서 덮어써지는 재사용 버퍼다.
 *
 * @param maxDimension 0 보다 크면 긴 변이 이 값 이하가 되도록 정수 배수로 솎아낸다.
 *                     detector 가 어차피 letterbox 로 축소하므로, 그 전에 줄여두면
 *                     변환 비용이 배수의 제곱만큼 줄어든다. bbox 는 정규화 좌표라 영향 없음.
 */
class YuvToRgbConverter(private val maxDimension: Int = 0) {

    private var rgb = ByteArray(0)
    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)

    fun convert(image: ImageProxy, rotationDegrees: Int): CvFrame {
        require(image.format == ImageFormat.YUV_420_888) {
            "Expected YUV_420_888 analysis frames, got format=${image.format}"
        }
        val rotation = ((rotationDegrees % 360) + 360) % 360
        require(rotation % 90 == 0) { "rotationDegrees must be a multiple of 90: $rotationDegrees" }

        val sourceWidth = image.width
        val sourceHeight = image.height
        val step = samplingStep(sourceWidth, sourceHeight)
        val sampledWidth = (sourceWidth + step - 1) / step
        val sampledHeight = (sourceHeight + step - 1) / step

        val swapAxes = rotation == 90 || rotation == 270
        val outputWidth = if (swapAxes) sampledHeight else sampledWidth
        val outputHeight = if (swapAxes) sampledWidth else sampledHeight

        val requiredSize = outputWidth * outputHeight * 3
        if (rgb.size != requiredSize) rgb = ByteArray(requiredSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        yBytes = copyPlane(yPlane.buffer, yBytes)
        uBytes = copyPlane(uPlane.buffer, uBytes)
        vBytes = copyPlane(vPlane.buffer, vBytes)

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        for (sampledY in 0 until sampledHeight) {
            val sourceY = sampledY * step
            val yRowOffset = sourceY * yRowStride
            val chromaRow = sourceY / 2
            val uRowOffset = chromaRow * uRowStride
            val vRowOffset = chromaRow * vRowStride

            for (sampledX in 0 until sampledWidth) {
                val sourceX = sampledX * step
                val chromaColumn = sourceX / 2

                val luma = yBytes[yRowOffset + sourceX * yPixelStride].toInt() and 0xFF
                val chromaU = (uBytes[uRowOffset + chromaColumn * uPixelStride].toInt() and 0xFF) - 128
                val chromaV = (vBytes[vRowOffset + chromaColumn * vPixelStride].toInt() and 0xFF) - 128

                // full-range BT.601, 고정소수점(<<16). 검출용이라 이 정도 정확도로 충분하다.
                val red = luma + ((91881 * chromaV) shr 16)
                val green = luma - ((22554 * chromaU + 46802 * chromaV) shr 16)
                val blue = luma + ((116130 * chromaU) shr 16)

                val destinationX: Int
                val destinationY: Int
                when (rotation) {
                    90 -> {
                        destinationX = sampledHeight - 1 - sampledY
                        destinationY = sampledX
                    }
                    180 -> {
                        destinationX = sampledWidth - 1 - sampledX
                        destinationY = sampledHeight - 1 - sampledY
                    }
                    270 -> {
                        destinationX = sampledY
                        destinationY = sampledWidth - 1 - sampledX
                    }
                    else -> {
                        destinationX = sampledX
                        destinationY = sampledY
                    }
                }

                val offset = (destinationY * outputWidth + destinationX) * 3
                rgb[offset] = clampToByte(red)
                rgb[offset + 1] = clampToByte(green)
                rgb[offset + 2] = clampToByte(blue)
            }
        }

        return CvFrame(rgb, outputWidth, outputHeight)
    }

    private fun samplingStep(width: Int, height: Int): Int {
        if (maxDimension <= 0) return 1
        val longestSide = maxOf(width, height)
        if (longestSide <= maxDimension) return 1
        // 정수 배수로만 솎아낸다 — 보간 없이 인덱싱만으로 끝내기 위해.
        return (longestSide + maxDimension - 1) / maxDimension
    }

    private fun copyPlane(buffer: java.nio.ByteBuffer, reusable: ByteArray): ByteArray {
        val source = buffer.duplicate()
        source.rewind()
        val size = source.remaining()
        val destination = if (reusable.size >= size) reusable else ByteArray(size)
        source.get(destination, 0, size)
        return destination
    }

    private fun clampToByte(value: Int): Byte =
        when {
            value < 0 -> 0.toByte()
            value > 255 -> 255.toByte()
            else -> value.toByte()
        }
}
