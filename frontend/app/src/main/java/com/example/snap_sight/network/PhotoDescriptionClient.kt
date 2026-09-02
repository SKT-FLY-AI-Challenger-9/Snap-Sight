// 이 파일: 촬영 업로드 후 "방금 뭐가 찍혔는지" 한 줄 설명을 서버에 물어보는 담당.
// 무거운 비교 결과(CaptureResultClient)와 병렬로 돌아 설명을 먼저 들려주기 위한 클라이언트다 (#76).
package com.example.snap_sight.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 대표 컷 한 줄 설명 폴링 클라이언트.
 *
 * 백엔드 계약은 result 폴링과 동일 규약:
 *  - GET {baseUrl}/api/capture/{session_id}/description
 *  - 404 → 재시도 안 함 / pending → retry_after_seconds 뒤 재조회 / done → description 사용
 *  - description이 null이면 생성 실패 — 안내 없이 조용히 넘어간다
 */
class PhotoDescriptionClient(
    private val baseUrl: String? = null, // null = 요청 시점에 BackendConfig.baseUrl 사용
    private val client: OkHttpClient = SnapSightHttp.client(connectSeconds = 5, readSeconds = 10),
) {

    interface Callback {
        /** 설명 생성 완료. 메인 스레드에서 호출된다. null이면 생성 실패였다는 뜻. */
        fun onDone(description: String?)
        fun onDone(sessionId: String, description: String?) = onDone(description)

        /** 404·타임아웃 등으로 폴링을 접음. 촬영 흐름에 영향 없어야 하므로 안내하지 않는다. */
        fun onGaveUp(reason: String)
        fun onGaveUp(sessionId: String, reason: String) = onGaveUp(reason)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val poller = SessionPoller<String?>(
        mainHandler = mainHandler,
        threadName = "SnapSight-DescriptionPoll",
        totalTimeoutMs = TOTAL_TIMEOUT_MS,
        initialBackoffMs = DEFAULT_RETRY_MS,
        maxBackoffMs = MAX_RETRY_MS,
    )

    /** 기존 호출 호환. 통합 metadata 경로로 이관하기 전까지 유지한다. */
    fun pollDescription(sessionId: String, callback: Callback): NetworkRequestHandle =
        pollDescription(sessionId, expectedRevision = null, callback = callback)

    fun pollDescription(
        sessionId: String,
        expectedRevision: Long?,
        callback: Callback,
    ): NetworkRequestHandle = poller.poll(
        sessionId = sessionId,
        fetch = { handle ->
            when (val decision = fetchDecision(sessionId, handle)) {
                is Decision.Done -> when {
                    expectedRevision != null && decision.captureRevision != expectedRevision ->
                        PollOutcome.GaveUp(
                            "capture revision 불일치(expected=$expectedRevision, " +
                                "actual=${decision.captureRevision})"
                        )
                    else -> PollOutcome.Done(decision.description)
                }
                is Decision.Failed -> PollOutcome.GaveUp(decision.reason)
                is Decision.NotFound -> PollOutcome.GaveUp("세션 없음(404) — 재시도 안 함")
                is Decision.Pending -> PollOutcome.Pending(decision.retryAfterMs)
            }
        },
        onTransientError = { error ->
            Log.w(TAG, "설명 조회 실패, 백오프 후 재시도 [$sessionId]: ${error.message}")
        },
        onDone = { callback.onDone(sessionId, it) },
        onGaveUp = { callback.onGaveUp(sessionId, it) },
    )

    fun cancel(sessionId: String) = poller.cancel(sessionId)

    fun cancelAll() = poller.cancelAll()

    private fun fetchDecision(sessionId: String, handle: NetworkRequestHandle): Decision {
        val request = Request.Builder()
            .url("${baseUrl ?: BackendConfig.baseUrl}/api/capture/$sessionId/description")
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
            val description: String?,
            val captureRevision: Long,
            val finalFrameId: String,
        ) : Decision()
        data class Pending(val retryAfterMs: Long) : Decision()
        data class Failed(val reason: String) : Decision()
        object NotFound : Decision()
    }

    companion object {
        private const val TAG = "PhotoDescriptionClient"

        // Haiku 1장 설명 실측 ~26초(API 느린 시간대) + 여유
        internal const val TOTAL_TIMEOUT_MS = 45_000L
        internal const val DEFAULT_RETRY_MS = 1_000L
        internal const val MAX_RETRY_MS = 8_000L

        internal fun parseDecision(json: String): Decision {
            val obj = JSONObject(json)
            val status = obj.optString("status")
            return when (status) {
                "done" -> Decision.Done(
                    description = obj.optString("description").takeIf { it.isNotBlank() },
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
                    "unknown description status: ${status.ifBlank { "missing" }}"
                )
            }
        }
    }
}
