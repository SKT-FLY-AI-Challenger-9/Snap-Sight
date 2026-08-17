// 이 파일: 카메라 원본 프레임(YUV 형식)을 일반 사진 파일(JPEG)로 바꾸는 변환 도구.
// 카메라는 사진을 특수한 형식으로 주기 때문에 저장·전송 전에 변환이 필요하다.
// 링 버퍼가 후보 프레임을 담을 때 이 변환을 쓴다.
package com.example.snap_sight.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * CameraX 분석 스트림의 YUV_420_888 프레임을 JPEG 바이트로 변환한다.
 * 링 버퍼가 분석 스레드에서 호출하므로 할당을 최소화한 단순 구현.
 */
internal fun ImageProxy.toJpegBytes(quality: Int): ByteArray {
    val nv21 = toNv21()
    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)
    return out.toByteArray()
}

/** row/pixel stride 를 고려해 YUV_420_888 → NV21 로 변환한다. */
private fun ImageProxy.toNv21(): ByteArray {
    val ySize = width * height
    val nv21 = ByteArray(ySize + ySize / 2)

    val yPlane = planes[0]
    val yBuffer = yPlane.buffer.duplicate()
    var pos = 0
    if (yPlane.pixelStride == 1 && yPlane.rowStride == width) {
        yBuffer.position(0)
        yBuffer.get(nv21, 0, ySize)
        pos = ySize
    } else {
        for (row in 0 until height) {
            yBuffer.position(row * yPlane.rowStride)
            if (yPlane.pixelStride == 1) {
                yBuffer.get(nv21, pos, width)
                pos += width
            } else {
                for (col in 0 until width) {
                    nv21[pos++] = yBuffer.get(row * yPlane.rowStride + col * yPlane.pixelStride)
                }
            }
        }
    }

    // NV21 은 V/U 인터리브
    val uPlane = planes[1]
    val vPlane = planes[2]
    val uBuffer = uPlane.buffer.duplicate()
    val vBuffer = vPlane.buffer.duplicate()
    val chromaHeight = height / 2
    val chromaWidth = width / 2
    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            nv21[pos++] = vBuffer.get(row * vPlane.rowStride + col * vPlane.pixelStride)
            nv21[pos++] = uBuffer.get(row * uPlane.rowStride + col * uPlane.pixelStride)
        }
    }
    return nv21
}
