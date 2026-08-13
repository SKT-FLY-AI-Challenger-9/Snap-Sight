package com.example.snap_sight.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.snap_sight.cv.FrameProcessor

/**
 * 링 버퍼 등 CV 추론 외의 프레임 소비자가 구현하는 내부 인터페이스.
 * [FrameProcessor] 와 동일한 스레딩 계약을 따른다 (동기 처리, ImageProxy 보관 금지).
 */
interface FrameSink {
    fun onFrame(image: ImageProxy, rotationDegrees: Int, timestampMs: Long)
}

/**
 * CameraX 의 ImageAnalysis 스트림을 [FrameProcessor](② CV)와 [FrameSink](링 버퍼)로
 * 분배하는 어댑터.
 *
 * - ImageProxy 의 close 책임은 여기(⑤)가 진다. 소비자는 신경 쓸 필요 없음.
 * - 소비자 교체는 스레드 안전하며, attach/detach 라이프사이클 콜백을 보장한다.
 */
internal class FrameAnalysisAdapter : ImageAnalysis.Analyzer {

    @Volatile
    private var processor: FrameProcessor? = null

    @Volatile
    private var sink: FrameSink? = null

    fun setProcessor(next: FrameProcessor?) {
        val prev = processor
        if (prev === next) return
        processor = next
        prev?.onDetached()
        next?.onAttached()
    }

    fun setSink(next: FrameSink?) {
        sink = next
    }

    override fun analyze(image: ImageProxy) {
        image.use { proxy ->
            val rotation = proxy.imageInfo.rotationDegrees
            sink?.onFrame(proxy, rotation, System.currentTimeMillis())
            processor?.onFrame(proxy, rotation)
        }
    }
}
