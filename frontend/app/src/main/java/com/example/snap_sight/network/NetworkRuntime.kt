package com.example.snap_sight.network

import android.os.Handler
import com.example.snap_sight.BuildConfig
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 앱 전체가 하나의 dispatcher/connection pool/thread pool을 공유하도록 만드는 OkHttp 팩토리.
 * [OkHttpClient.newBuilder]로 만든 클라이언트는 timeout은 달라도 기반 리소스는 공유한다.
 */
internal object SnapSightHttp {
    private val shared = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            // 빌드에 포함된 값은 APK에서 추출 가능하므로 강한 배포 비밀이 아니다. 값은 절대 로그하지 않는다.
            chain.proceed(chain.request().withSnapSightToken(BuildConfig.SNAPSIGHT_API_TOKEN))
        }
        .build()

    fun client(
        connectSeconds: Long,
        readSeconds: Long,
        writeSeconds: Long = readSeconds,
    ): OkHttpClient = shared.newBuilder()
        .connectTimeout(connectSeconds, TimeUnit.SECONDS)
        .readTimeout(readSeconds, TimeUnit.SECONDS)
        .writeTimeout(writeSeconds, TimeUnit.SECONDS)
        .build()
}

internal fun Request.withSnapSightToken(token: String): Request =
    if (token.isBlank()) this
    else newBuilder().header("X-Snap-Sight-Token", token).build()

/** Thread.interrupt와 현재 OkHttp [Call.cancel]을 함께 묶는 가벼운 취소 토큰. */
class NetworkRequestHandle internal constructor(val sessionId: String? = null) {
    private val cancelled = AtomicBoolean(false)
    private val call = AtomicReference<Call?>(null)
    private val worker = AtomicReference<Thread?>(null)

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        call.getAndSet(null)?.cancel()
        worker.getAndSet(null)?.interrupt()
    }

    internal fun attachWorker(thread: Thread): Boolean {
        worker.set(thread)
        if (!isCancelled) return true
        worker.compareAndSet(thread, null)
        thread.interrupt()
        return false
    }

    internal fun clearWorker(thread: Thread) {
        worker.compareAndSet(thread, null)
    }

    internal fun attachCall(next: Call): Boolean {
        call.set(next)
        if (!isCancelled) return true
        if (call.compareAndSet(next, null)) next.cancel()
        return false
    }

    internal fun clearCall(finished: Call) {
        call.compareAndSet(finished, null)
    }

    /** 취소/interrupt를 정상 종료로 흡수하는 대기. */
    internal fun await(delayMs: Long): Boolean {
        if (isCancelled) return false
        return try {
            Thread.sleep(delayMs.coerceAtLeast(0L))
            !isCancelled
        } catch (_: InterruptedException) {
            false
        }
    }
}

/** 같은 종류의 요청에서 동일 sessionId의 이전 작업을 교체·취소하고 콜백도 격리한다. */
internal class SessionRequestRegistry {
    private val active = ConcurrentHashMap<String, NetworkRequestHandle>()

    fun replace(sessionId: String): NetworkRequestHandle {
        val next = NetworkRequestHandle(sessionId)
        active.put(sessionId, next)?.cancel()
        return next
    }

    fun isCurrent(sessionId: String, handle: NetworkRequestHandle): Boolean =
        !handle.isCancelled && active[sessionId] === handle

    fun finish(sessionId: String, handle: NetworkRequestHandle): Boolean {
        val wasCurrent = active.remove(sessionId, handle)
        return wasCurrent && !handle.isCancelled
    }

    fun remove(sessionId: String, handle: NetworkRequestHandle) {
        active.remove(sessionId, handle)
    }

    fun cancel(sessionId: String) {
        active.remove(sessionId)?.cancel()
    }

    fun cancelAll() {
        val snapshot = active.values.toList()
        active.clear()
        snapshot.forEach(NetworkRequestHandle::cancel)
    }
}

/** 서버가 준 최소 주기를 존중하면서 연속 pending/오류에는 지수 백오프를 적용한다. */
internal class ExponentialBackoff(
    private val initialMs: Long,
    private val maxMs: Long,
    private val multiplier: Int = 2,
) {
    private var nextMs = initialMs

    init {
        require(initialMs > 0L)
        require(maxMs >= initialMs)
        require(multiplier >= 1)
    }

    fun next(serverMinimumMs: Long = 0L): Long {
        // maxMs는 앱 자체 backoff의 상한이다. 서버가 더 긴 최소 대기를 명시하면 그 값은 지킨다.
        val delay = maxOf(nextMs, serverMinimumMs)
        nextMs = (nextMs * multiplier).coerceAtMost(maxMs)
        return delay
    }

    fun reset() {
        nextMs = initialMs
    }
}

internal sealed class PollOutcome<out T> {
    data class Done<T>(val value: T) : PollOutcome<T>()
    data class Pending(val serverMinimumMs: Long) : PollOutcome<Nothing>()
    data class GaveUp(val reason: String) : PollOutcome<Nothing>()
}

/** Polling retries are intentionally narrow: overload/server failure and transport I/O only. */
internal fun isRetryablePollHttpCode(code: Int): Boolean =
    code == 429 || code in 500..599

/** Retry-After uses delta-seconds for this API. Invalid/date values fall back to local backoff. */
internal fun retryAfterMillis(value: String?): Long =
    value?.trim()?.toLongOrNull()
        ?.takeIf { it >= 0L }
        ?.let { seconds ->
            seconds.coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1_000L
        }
        ?: 0L

internal class RetryablePollHttpException(
    val statusCode: Int,
    val serverMinimumMs: Long,
) : IOException("HTTP $statusCode")

internal val PENDING_POLL_STATUSES: Set<String> =
    setOf("pending", "processing", "selecting")

private const val MAX_RETRY_AFTER_SECONDS = 24L * 60L * 60L

/** done payload가 요청한 capture revision의 canonical frame을 가리키는지 검사한다. */
internal fun terminalIdentityError(
    actualRevision: Long,
    finalFrameId: String,
    expectedRevision: Long?,
): String? = when {
    actualRevision < 1L || finalFrameId.isBlank() ->
        "완료 응답의 revision/final_frame_id가 없음"
    expectedRevision != null && actualRevision != expectedRevision ->
        "capture revision 불일치(expected=$expectedRevision, actual=$actualRevision)"
    else -> null
}

/** 세 폴링 API가 동일한 취소·세션 교체·백오프·메인 콜백 계약을 쓰게 한다. */
internal class SessionPoller<T>(
    private val mainHandler: Handler,
    private val threadName: String,
    private val totalTimeoutMs: Long,
    private val initialBackoffMs: Long,
    private val maxBackoffMs: Long,
) {
    private val registry = SessionRequestRegistry()

    fun poll(
        sessionId: String,
        fetch: (NetworkRequestHandle) -> PollOutcome<T>,
        onTransientError: (Throwable) -> Unit,
        onDone: (T) -> Unit,
        onGaveUp: (String) -> Unit,
    ): NetworkRequestHandle {
        val handle = registry.replace(sessionId)
        val worker = Thread({
            var terminalPosted = false
            try {
                val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMs)
                val backoff = ExponentialBackoff(initialBackoffMs, maxBackoffMs)
                while (!handle.isCancelled && System.nanoTime() < deadlineNs) {
                    val outcome = try {
                        fetch(handle)
                    } catch (t: Throwable) {
                        if (handle.isCancelled) return@Thread
                        if (t is IOException) {
                            onTransientError(t)
                            PollOutcome.Pending(
                                (t as? RetryablePollHttpException)?.serverMinimumMs ?: 0L
                            )
                        } else {
                            PollOutcome.GaveUp(
                                t.message?.takeIf { it.isNotBlank() }
                                    ?: "poll response processing failed"
                            )
                        }
                    }
                    when (outcome) {
                        is PollOutcome.Done -> {
                            terminalPosted = true
                            mainHandler.post {
                                if (registry.finish(sessionId, handle)) onDone(outcome.value)
                            }
                            return@Thread
                        }
                        is PollOutcome.GaveUp -> {
                            terminalPosted = true
                            mainHandler.post {
                                if (registry.finish(sessionId, handle)) onGaveUp(outcome.reason)
                            }
                            return@Thread
                        }
                        is PollOutcome.Pending -> {
                            val remainingMs = TimeUnit.NANOSECONDS.toMillis(
                                (deadlineNs - System.nanoTime()).coerceAtLeast(0L)
                            )
                            val delay = backoff.next(outcome.serverMinimumMs).coerceAtMost(remainingMs)
                            if (!handle.await(delay)) return@Thread
                        }
                    }
                }
                if (!handle.isCancelled) {
                    terminalPosted = true
                    mainHandler.post {
                        if (registry.finish(sessionId, handle)) {
                            onGaveUp("타임아웃(${totalTimeoutMs / 1000}초)")
                        }
                    }
                }
            } finally {
                handle.clearWorker(Thread.currentThread())
                if (!terminalPosted) registry.remove(sessionId, handle)
            }
        }, "$threadName-${sessionId.takeLast(8)}")
        if (handle.attachWorker(worker)) worker.start()
        return handle
    }

    fun cancel(sessionId: String) = registry.cancel(sessionId)

    fun cancelAll() = registry.cancelAll()
}

/** 실행 중 Call을 handle에 붙여 cancel()이 소켓 대기까지 즉시 깨우게 한다. */
internal inline fun <T> OkHttpClient.executeCancellable(
    handle: NetworkRequestHandle,
    request: Request,
    block: (Response) -> T,
): T {
    val call = newCall(request)
    check(handle.attachCall(call)) { "request cancelled" }
    return try {
        call.execute().use(block)
    } finally {
        handle.clearCall(call)
    }
}
