// 이 파일: 촬영 업로드 후 "AI가 더 나은 사진을 골랐는지" 결과를 서버에 물어보는 담당.
// 비교가 끝날 때까지 몇 초 간격으로 다시 물어보고(폴링), 끝나면 결과만 돌려준다.
// 실패하거나 오래 걸려도 촬영 흐름을 막지 않는다 — 조용히 포기하고 로그만 남긴다.
package com.example.snap_sight.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * ⑧ MLLM 비교 결과 폴링 클라이언트.
 *
 * 백엔드 계약 (`docs/backend-local-setup.md` "결과 조회 폴링", #51):
 *  - GET {baseUrl}/api/capture/{session_id}/result
 *  - 404 → 업로드된 적 없는 세션. 재시도하지 않는다
 *  - 200 + status="pending" → `retry_after_seconds`(기본 2초) 뒤 재조회
 *  - 200 + status="done" → `improved`·`reason` 사용
 *  - 전체 타임아웃은 30초 이상 — LLM 폴백 세션이 ~12초 걸리는 실측(#37) 반영
 *
 * 폴링은 자체 백그라운드 스레드에서 수행하고 콜백은 메인 스레드로 돌려준다.
 */
class CaptureResultClient(
    private val baseUrl: String? = null, // null = 요청 시점에 BackendConfig.baseUrl 사용
    private val client: OkHttpClient = SnapSightHttp.client(connectSeconds = 5, readSeconds = 10),
) {

    class ComparisonResult(
        val improved: Boolean,
        val reason: String?,
        val captureRevision: Long,
        val finalFrameId: String,
    )

    interface Callback {
        /** 비교 완료. 메인 스레드에서 호출된다. */
        fun onDone(result: ComparisonResult)
        fun onDone(sessionId: String, result: ComparisonResult) = onDone(result)

        /** 404·타임아웃 등으로 폴링을 접음. 촬영 흐름에 영향 없어야 하므로 안내는 하지 않는 것을 권장. */
        fun onGaveUp(reason: String)
        fun onGaveUp(sessionId: String, reason: String) = onGaveUp(reason)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val poller = SessionPoller<ComparisonResult>(
        mainHandler = mainHandler,
        threadName = "SnapSight-ResultPoll",
        totalTimeoutMs = TOTAL_TIMEOUT_MS,
        initialBackoffMs = DEFAULT_RETRY_MS,
        maxBackoffMs = MAX_RETRY_MS,
    )

    /** 기존 호출 호환. 새 코드는 revision 검증 overload를 사용한다. */
    fun pollResult(sessionId: String, callback: Callback): NetworkRequestHandle =
        pollResult(sessionId, expectedRevision = null, callback = callback)

    fun pollResult(
        sessionId: String,
        expectedRevision: Long?,
        callback: Callback,
    ): NetworkRequestHandle = poller.poll(
        sessionId = sessionId,
        fetch = { handle ->
            when (val decision = fetchDecision(sessionId, handle)) {
                is Decision.Done -> terminalIdentityError(
                    decision.captureRevision,
                    decision.finalFrameId,
                    expectedRevision,
                )?.let { PollOutcome.GaveUp(it) } ?: PollOutcome.Done(
                        ComparisonResult(
                            improved = decision.improved,
                            reason = decision.reason,
                            captureRevision = decision.captureRevision,
                            finalFrameId = decision.finalFrameId,
                        )
                    )
                is Decision.Failed -> PollOutcome.GaveUp(decision.reason)
                is Decision.NotFound -> PollOutcome.GaveUp("세션 없음(404) — 재시도 안 함")
                is Decision.Pending -> PollOutcome.Pending(decision.retryAfterMs)
            }
        },
        onTransientError = { error ->
            Log.w(TAG, "결과 조회 실패, 백오프 후 재시도 [$sessionId]: ${error.message}")
        },
        onDone = { callback.onDone(sessionId, it) },
        onGaveUp = { callback.onGaveUp(sessionId, it) },
    )

    fun cancel(sessionId: String) = poller.cancel(sessionId)

    fun cancelAll() = poller.cancelAll()

    private fun fetchDecision(sessionId: String, handle: NetworkRequestHandle): Decision {
        val request = Request.Builder()
            .url("${baseUrl ?: BackendConfig.baseUrl}/api/capture/$sessionId/result")
            .get()
            .build()
        return client.executeCancellable(handle, request) { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> parseDecision(body)
                response.code == 404 -> Decision.NotFound
                isRetryablePollHttpCode(response.code) -> throw RetryablePollHttpException(
                    statusCode = response.code,
                    serverMinimumMs = retryAfterMillis(response.header("Retry-After")),
                )
                else -> Decision.Failed("HTTP ${response.code} — ${body.take(200)}")
            }
        }
    }

    /** 폴링 판정 (순수 로직, JVM 테스트 대상). */
    internal sealed class Decision {
        data class Done(
            val improved: Boolean,
            val reason: String?,
            val captureRevision: Long,
            val finalFrameId: String,
        ) : Decision()
        data class Pending(val retryAfterMs: Long) : Decision()
        data class Failed(val reason: String) : Decision()
        object NotFound : Decision()
    }

    companion object {
        private const val TAG = "CaptureResultClient"

        // LLM 폴백 세션 실측 ~12초(#37) + 여유. 계약상 30초 미만으로 잡으면 안 된다.
        internal const val TOTAL_TIMEOUT_MS = 45_000L
        internal const val DEFAULT_RETRY_MS = 2_000L
        internal const val MAX_RETRY_MS = 8_000L

        internal fun parseDecision(json: String): Decision {
            val obj = JSONObject(json)
            val status = obj.optString("status")
            return when (status) {
                "done" -> Decision.Done(
                    improved = obj.optBoolean("improved", false),
                    reason = obj.optString("reason").takeIf { it.isNotBlank() },
                    captureRevision = obj.optLong("capture_revision", -1L),
                    finalFrameId = obj.optString("final_frame_id"),
                )
                "failed" -> Decision.Failed(
                    obj.optString("reason").takeIf { it.isNotBlank() }
                        ?: "capture pipeline failed"
                )
                in PENDING_POLL_STATUSES -> {
                    val seconds = obj.optDouble("retry_after_seconds", DEFAULT_RETRY_MS / 1000.0)
                    Decision.Pending(retryAfterMs = (seconds * 1000).toLong().coerceAtLeast(500L))
                }
                else -> Decision.Failed(
                    "unknown capture result status: ${status.ifBlank { "missing" }}"
                )
            }
        }
    }
}
