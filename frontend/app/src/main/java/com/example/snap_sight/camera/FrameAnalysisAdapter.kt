// 이 파일: 카메라가 뽑아내는 영상 프레임을 AI 분석기에 건네주는 중간 다리.
// 분석기를 실행 중에 갈아끼울 수 있게 하고,
// 프레임 메모리를 다 쓴 뒤 돌려주는(닫는) 책임도 여기서 진다.
package com.example.snap_sight.camera

import android.os.SystemClock
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

    // 추론 중 detach(TFLite 해제)가 끼어들면 네이티브 크래시 — onFrame과 교체를 배타 실행한다
    private val processorLock = Any()

    private var processor: FrameProcessor? = null

    @Volatile
    private var sink: FrameSink? = null

    /**
     * CameraX use case를 다시 묶지 않고도 전달을 즉시 멈추는 빠른 게이트.
     * false일 때도 전달받은 [ImageProxy]는 즉시 닫아 CameraX 파이프라인을 막지 않는다.
     */
    @Volatile
    private var enabled = true

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setProcessor(next: FrameProcessor?) {
        synchronized(processorLock) {
            val prev = processor
            if (prev === next) return
            processor = next
            prev?.onDetached()
            next?.onAttached()
        }
    }

    fun setSink(next: FrameSink?) {
        sink = next
    }

    override fun analyze(image: ImageProxy) {
        image.use { proxy ->
            if (!enabled) return@use
            val rotation = proxy.imageInfo.rotationDegrees
            val timestampMs = SystemClock.elapsedRealtime()
            // 조준 판정이 시간 민감하므로 CV를 먼저 수행하고, 저주기 JPEG 후보 저장은 뒤에 둔다.
            synchronized(processorLock) { processor?.onFrame(proxy, rotation) }
            sink?.onFrame(proxy, rotation, timestampMs)
        }
    }
}
