package com.example.snap_sight.camera

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageProxy

/**
 * 촬영 시점 "직전 1초 + 직후 1초" 후보 프레임을 확보하는 링 버퍼 (README 파이프라인 7단계).
 *
 * - 분석 스트림에서 [minIntervalMs] 간격으로 프레임을 JPEG 로 압축해 보관한다.
 * - [requestCandidates] 호출(=셔터) 후 직후 창이 채워지면 전후 창 안의 프레임을
 *   최대 [maxCandidates] 장으로 골라 메인 스레드 콜백으로 돌려준다.
 * - 후보는 MLLM 비교용이므로 원본 화질일 필요가 없다 (분석 해상도 640x480 사용).
 *
 * 스레딩: [onFrame] 은 분석 스레드, [requestCandidates]/[flush] 는 메인 스레드에서 호출.
 */
class RingFrameBuffer(
    private val preWindowMs: Long = 1_000,
    private val postWindowMs: Long = 1_000,
    private val minIntervalMs: Long = 150,
    private val jpegQuality: Int = 80,
    private val maxCandidates: Int = 6,
) : FrameSink {

    /** 후보 프레임 1장. [rotationDegrees] 는 정방향 회전값 (JPEG 자체는 회전 안 됨). */
    class Frame(val jpeg: ByteArray, val timestampMs: Long, val rotationDegrees: Int)

    private val lock = Any()
    private val frames = ArrayDeque<Frame>()

    private var pendingShutterMs: Long = -1
    private var pendingCallback: ((List<Frame>) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onFrame(image: ImageProxy, rotationDegrees: Int, timestampMs: Long) {
        val shouldStore: Boolean
        synchronized(lock) {
            shouldStore = frames.isEmpty() || timestampMs - frames.last().timestampMs >= minIntervalMs
        }
        if (shouldStore) {
            val jpeg = image.toJpegBytes(jpegQuality)
            synchronized(lock) {
                frames.addLast(Frame(jpeg, timestampMs, rotationDegrees))
                evictLocked(timestampMs)
            }
        }
        maybeCompletePending(timestampMs)
    }

    /**
     * 셔터 시점 등록. 직후 창(postWindowMs)이 채워지는 대로
     * [callback] 이 메인 스레드에서 호출된다. 진행 중인 요청이 있으면 무시.
     */
    fun requestCandidates(shutterTimeMs: Long, callback: (List<Frame>) -> Unit) {
        synchronized(lock) {
            if (pendingCallback != null) {
                Log.w(TAG, "이미 후보 수집 중 — 요청 무시")
                return
            }
            pendingShutterMs = shutterTimeMs
            pendingCallback = callback
        }
        // 카메라가 멈춰 프레임이 더 안 들어와도 콜백은 보장한다.
        mainHandler.postDelayed({ maybeCompletePending(Long.MAX_VALUE) }, postWindowMs + TIMEOUT_SLACK_MS)
    }

    /** 세션 취소 등으로 즉시 정리할 때. 진행 중 요청은 현재 시점 기준으로 마감. */
    fun flush() {
        maybeCompletePending(Long.MAX_VALUE)
        synchronized(lock) { frames.clear() }
    }

    private fun maybeCompletePending(nowMs: Long) {
        val result: List<Frame>
        val callback: (List<Frame>) -> Unit
        synchronized(lock) {
            val cb = pendingCallback ?: return
            val shutterMs = pendingShutterMs
            if (nowMs < shutterMs + postWindowMs) return
            result = frames
                .filter { it.timestampMs in (shutterMs - preWindowMs)..(shutterMs + postWindowMs) }
                .let(::subsample)
            callback = cb
            pendingCallback = null
            pendingShutterMs = -1
        }
        mainHandler.post { callback(result) }
    }

    /** 시간축에서 고르게 최대 [maxCandidates] 장을 고른다. */
    private fun subsample(candidates: List<Frame>): List<Frame> {
        if (candidates.size <= maxCandidates) return candidates
        val step = (candidates.size - 1).toDouble() / (maxCandidates - 1)
        return (0 until maxCandidates).map { candidates[(it * step).toInt()] }
    }

    private fun evictLocked(nowMs: Long) {
        // 셔터 대기 중엔 전 창 시작점 이전만, 평상시엔 전 창 밖 프레임을 버린다.
        val keepFrom = if (pendingShutterMs > 0) {
            pendingShutterMs - preWindowMs
        } else {
            nowMs - preWindowMs - EVICT_SLACK_MS
        }
        while (frames.isNotEmpty() && frames.first().timestampMs < keepFrom) {
            frames.removeFirst()
        }
    }

    private companion object {
        const val TAG = "RingFrameBuffer"
        const val EVICT_SLACK_MS = 300L
        const val TIMEOUT_SLACK_MS = 700L
    }
}
