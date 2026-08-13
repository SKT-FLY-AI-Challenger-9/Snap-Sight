package com.example.snap_sight.cv

import android.util.Log
import androidx.camera.core.ImageProxy

/**
 * ② CV 모듈이 들어오기 전까지 파이프라인 동작을 확인하기 위한 기본 구현.
 * 1초마다 프레임 처리 FPS 와 해상도를 로그로 남긴다.
 *
 * Logcat 필터: tag:SnapSightFrames
 */
class LoggingFrameProcessor : FrameProcessor {

    private var frameCount = 0
    private var windowStartMs = 0L

    override fun onAttached() {
        Log.i(TAG, "프레임 파이프라인 연결됨")
        windowStartMs = System.currentTimeMillis()
        frameCount = 0
    }

    override fun onFrame(image: ImageProxy, rotationDegrees: Int) {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - windowStartMs
        if (elapsed >= 1000) {
            val fps = frameCount * 1000f / elapsed
            Log.d(TAG, "분석 스트림: %.1f fps, %dx%d, rotation=%d".format(
                fps, image.width, image.height, rotationDegrees))
            frameCount = 0
            windowStartMs = now
        }
    }

    override fun onDetached() {
        Log.i(TAG, "프레임 파이프라인 분리됨")
    }

    private companion object {
        const val TAG = "SnapSightFrames"
    }
}
