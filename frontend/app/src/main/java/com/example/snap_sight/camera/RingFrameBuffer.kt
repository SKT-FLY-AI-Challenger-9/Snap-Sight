// 이 파일: 셔터 직전·직후의 저해상도 후보 프레임을 제한된 비용으로 보관하는 순환 저장소.
package com.example.snap_sight.camera

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageProxy

/**
 * 촬영 시점 "직전 [preWindowMs] + 직후 [postWindowMs]" 후보 프레임 버퍼.
 *
 * 중요한 전력 계약:
 * - 기본값은 [Mode.OFF]이며, 이때 [onFrame]은 JPEG 인코더를 절대 호출하지 않는다.
 * - 조준이 시작되면 [startPreCapture]로 PRE_CAPTURE를 켠다.
 * - 셔터에서 [requestCandidates]를 부르면 POST_CAPTURE로 전환되고 완료 뒤 자동으로 OFF가 된다.
 * - 한 번에 JPEG 작업은 1개뿐이고 저장 배열도 [maxBufferedFrames]를 넘지 않는다.
 *
 * [onFrame]은 보통 CameraX 단일 분석 executor에서 호출되지만, 상태 변경은 메인 스레드에서
 * 들어오므로 모든 공유 상태는 [lock]으로 보호한다. 인코딩 중 취소되면 완성된 JPEG는 버린다.
 */
class RingFrameBuffer(
    private val preWindowMs: Long = 1_000,
    private val postWindowMs: Long = 1_000,
    /** PRE_CAPTURE 샘플 주기. 예전 minIntervalMs 위치를 유지해 positional 호출도 호환한다. */
    private val minIntervalMs: Long = DEFAULT_PRE_CAPTURE_INTERVAL_MS,
    private val jpegQuality: Int = 80,
    private val maxCandidates: Int = 6,
    private val postCaptureIntervalMs: Long = DEFAULT_POST_CAPTURE_INTERVAL_MS,
    private val maxBufferedFrames: Int = DEFAULT_MAX_BUFFERED_FRAMES,
) : FrameSink {

    enum class Mode { OFF, PRE_CAPTURE, POST_CAPTURE }

    /** 후보 프레임 1장. [rotationDegrees]는 정방향 회전값이며 JPEG 자체는 회전하지 않는다. */
    class Frame(val jpeg: ByteArray, val timestampMs: Long, val rotationDegrees: Int)

    data class Stats(
        val mode: Mode,
        val bufferedFrames: Int,
        val encodedFrames: Long,
        val skippedWhileOff: Long,
        val skippedByCadence: Long,
        val skippedWhileBusy: Long,
    )

    private data class Pending(
        val shutterMs: Long,
        val generation: Long,
        val callback: (List<Frame>) -> Unit,
    )

    private val lock = Any()
    private val frames = ArrayDeque<Frame>()
    private val cadence = FrameSamplingGate(minIntervalMs, postCaptureIntervalMs)
    private val encoder = YuvJpegEncoder()

    private var currentMode = Mode.OFF
    private var generation = 0L
    private var pending: Pending? = null
    private var timeoutRunnable: Runnable? = null

    private var encodedFrames = 0L
    private var skippedWhileOff = 0L
    private var skippedByCadence = 0L
    private var skippedWhileBusy = 0L

    // JVM 단위 테스트에서 모드·cadence만 검사할 때 Android Looper를 건드리지 않도록 지연 생성한다.
    private val mainHandlerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }
    private val mainHandler: Handler get() = mainHandlerDelegate.value

    val mode: Mode get() = synchronized(lock) { currentMode }
    val isEnabled: Boolean get() = mode != Mode.OFF

    init {
        require(preWindowMs >= 0L)
        require(postWindowMs >= 0L)
        require(minIntervalMs > 0L)
        require(postCaptureIntervalMs > 0L)
        require(jpegQuality in 1..100)
        require(maxCandidates > 0)
        require(maxBufferedFrames >= maxCandidates)
    }

    /** 새 조준 세션의 pre-buffer를 시작한다. 기존 세션의 프레임과 pending callback은 폐기한다. */
    fun startPreCapture() {
        synchronized(lock) {
            generation++
            cancelPendingLocked()
            frames.clear()
            cadence.reset()
            currentMode = Mode.PRE_CAPTURE
        }
    }

    /** 간단한 호환 API. true는 [startPreCapture], false는 [disable]과 같다. */
    fun setEnabled(enabled: Boolean) {
        if (enabled) startPreCapture() else disable()
    }

    /**
     * 모든 JPEG 작업을 중지한다. 진행 중 후보 요청은 callback 없이 취소된다.
     * 이미 인코딩 중인 한 프레임은 중단할 수 없지만 generation 검증에서 결과가 버려진다.
     */
    fun disable(clearFrames: Boolean = true) {
        synchronized(lock) {
            generation++
            currentMode = Mode.OFF
            cadence.reset()
            cancelPendingLocked()
            if (clearFrames) frames.clear()
        }
    }

    override fun onFrame(image: ImageProxy, rotationDegrees: Int, timestampMs: Long) {
        var reservationGeneration: Long? = null
        var earlyCompletion: Completion? = null
        synchronized(lock) {
            when (cadence.tryAcquire(currentMode, timestampMs)) {
                FrameSamplingGate.Result.DISABLED -> {
                    skippedWhileOff++
                    return
                }
                FrameSamplingGate.Result.TOO_SOON -> {
                    skippedByCadence++
                    earlyCompletion = maybeCompletePendingLocked(timestampMs)
                }
                FrameSamplingGate.Result.BUSY -> {
                    skippedWhileBusy++
                    return
                }
                FrameSamplingGate.Result.ACQUIRED -> {
                    reservationGeneration = generation
                }
            }
        }
        val acquiredGeneration = reservationGeneration
        if (acquiredGeneration == null) {
            earlyCompletion?.let(::dispatch)
            return
        }

        val jpeg = try {
            encoder.encode(image, jpegQuality)
        } catch (t: Throwable) {
            Log.w(TAG, "후보 프레임 JPEG 인코딩 실패", t)
            null
        }

        val completion = synchronized(lock) {
            cadence.release()
            if (jpeg != null && generation == acquiredGeneration && currentMode != Mode.OFF) {
                frames.addLast(Frame(jpeg, timestampMs, rotationDegrees))
                encodedFrames++
                evictLocked(timestampMs)
            }
            // 취소 전 프레임의 완료가 새 세션의 post-window를 마감해서는 안 된다.
            if (generation == acquiredGeneration) maybeCompletePendingLocked(timestampMs) else null
        }
        completion?.let(::dispatch)
    }

    /**
     * 셔터 시점을 등록하고 post-window 수집을 시작한다.
     * 이미 요청 중이면 false를 반환하며 새 callback을 보관하지 않는다.
     */
    fun requestCandidates(shutterTimeMs: Long, callback: (List<Frame>) -> Unit): Boolean {
        synchronized(lock) {
            if (pending != null) {
                Log.w(TAG, "이미 후보 수집 중 — 요청 무시")
                return false
            }
            if (currentMode == Mode.OFF) {
                // pre-buffer 없이 즉시 셔터가 들어온 경우에도 post-window는 수집한다.
                generation++
                frames.clear()
                cadence.reset()
            }
            currentMode = Mode.POST_CAPTURE
            val requestGeneration = generation
            pending = Pending(shutterTimeMs, requestGeneration, callback)
            val timeout = Runnable {
                val completion = synchronized(lock) timeoutLock@{
                    val active = pending
                    if (active == null || active.generation != requestGeneration) return@timeoutLock null
                    completePendingLocked(active)
                }
                completion?.let(::dispatch)
            }
            timeoutRunnable = timeout
            mainHandler.postDelayed(timeout, postWindowMs + TIMEOUT_SLACK_MS)
        }
        return true
    }

    /** 이전 API 호환: 진행 중 요청은 현재 프레임으로 완료하고 저장 버퍼를 비운다. */
    fun flush() {
        val completion = synchronized(lock) {
            val active = pending
            val result = active?.let(::completePendingLocked)
            frames.clear()
            result
        }
        completion?.let(::dispatch)
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(
            mode = currentMode,
            bufferedFrames = frames.size,
            encodedFrames = encodedFrames,
            skippedWhileOff = skippedWhileOff,
            skippedByCadence = skippedByCadence,
            skippedWhileBusy = skippedWhileBusy,
        )
    }

    private data class Completion(
        val callback: (List<Frame>) -> Unit,
        val frames: List<Frame>,
    )

    /** lock 안에서만 호출. */
    private fun maybeCompletePendingLocked(nowMs: Long): Completion? {
        val active = pending ?: return null
        if (nowMs < active.shutterMs + postWindowMs) return null
        return completePendingLocked(active)
    }

    /** lock 안에서만 호출. */
    private fun completePendingLocked(active: Pending): Completion {
        val selected = evenlySubsample(
            frames.filter { it.timestampMs in (active.shutterMs - preWindowMs)..(active.shutterMs + postWindowMs) },
            maxCandidates,
        )
        pending = null
        timeoutRunnable?.let { if (mainHandlerDelegate.isInitialized()) mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        currentMode = Mode.OFF
        cadence.reset()
        generation++
        frames.clear()
        return Completion(active.callback, selected)
    }

    private fun dispatch(completion: Completion) {
        mainHandler.post { completion.callback(completion.frames) }
    }

    /** lock 안에서만 호출. */
    private fun cancelPendingLocked() {
        pending = null
        timeoutRunnable?.let { if (mainHandlerDelegate.isInitialized()) mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun evictLocked(nowMs: Long) {
        val shutterMs = pending?.shutterMs
        val keepFrom = if (shutterMs != null) shutterMs - preWindowMs
        else nowMs - preWindowMs - EVICT_SLACK_MS
        while (frames.isNotEmpty() && frames.first().timestampMs < keepFrom) frames.removeFirst()
        while (frames.size > maxBufferedFrames) frames.removeFirst()
    }

    private companion object {
        const val TAG = "RingFrameBuffer"
        const val EVICT_SLACK_MS = 300L
        const val TIMEOUT_SLACK_MS = 700L
        const val DEFAULT_PRE_CAPTURE_INTERVAL_MS = 333L
        const val DEFAULT_POST_CAPTURE_INTERVAL_MS = 200L
        const val DEFAULT_MAX_BUFFERED_FRAMES = 16
    }
}

/** 한 번에 한 인코딩만 허용하는 순수 Kotlin cadence gate. */
internal class FrameSamplingGate(
    private val preIntervalMs: Long,
    private val postIntervalMs: Long,
) {
    enum class Result { DISABLED, TOO_SOON, BUSY, ACQUIRED }

    private var lastAcceptedMs = Long.MIN_VALUE
    private var busy = false
    fun tryAcquire(mode: RingFrameBuffer.Mode, timestampMs: Long): Result {
        if (mode == RingFrameBuffer.Mode.OFF) return Result.DISABLED
        if (busy) return Result.BUSY
        val interval = if (mode == RingFrameBuffer.Mode.POST_CAPTURE) postIntervalMs else preIntervalMs
        if (lastAcceptedMs != Long.MIN_VALUE && timestampMs - lastAcceptedMs < interval) {
            return Result.TOO_SOON
        }
        lastAcceptedMs = timestampMs
        busy = true
        return Result.ACQUIRED
    }

    fun release() {
        busy = false
    }

    fun reset() {
        lastAcceptedMs = Long.MIN_VALUE
        // 이미 진행 중인 인코딩 permit은 유지한다. 세션 전환이 인코딩 두 개를 겹치게 하지 않는다.
    }
}

/** 시간축에서 고르게 최대 [limit]개를 고른다. */
internal fun <T> evenlySubsample(items: List<T>, limit: Int): List<T> {
    require(limit > 0)
    if (items.size <= limit) return items.toList()
    if (limit == 1) return listOf(items[items.size / 2])
    val step = (items.size - 1).toDouble() / (limit - 1)
    return (0 until limit).map { items[(it * step).toInt()] }
}
