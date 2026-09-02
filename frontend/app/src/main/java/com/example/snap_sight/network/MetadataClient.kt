// 이 파일: 촬영 업로드 후 검색용 상세 메타데이터(상세 설명 + 라벨)를 서버에 물어보는 담당.
// 도착하면 로컬 사진 인덱스(PhotoIndexStore)에 기록돼 오프라인 검색·상세 낭독에 쓰인다 (기능 3-B).
package com.example.snap_sight.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 상세 메타데이터 폴링 클라이언트.
 *
 * 백엔드 계약 (description/result 와 동일 규약):
 *  - GET {baseUrl}/api/capture/{session_id}/metadata
 *  - 404 → 재시도 안 함 / pending → retry_after_seconds 뒤 재조회 / done → payload 사용
 *  - long_description 이 null 이면 생성 실패 — 라벨만이라도 저장한다
 */
class MetadataClient(
    private val baseUrl: String? = null, // null = 요청 시점에 BackendConfig.baseUrl 사용
    private val client: OkHttpClient = SnapSightHttp.client(connectSeconds = 5, readSeconds = 10),
) {

    /** done 응답의 내용. 폐쇄형 계약 검증(사전 대조)은 서버가 이미 마쳤다. */
    data class Metadata(
        val briefDescription: String?,
        val longDescription: String?,
        val labels: List<String>,
        val customLabels: List<String>,
        val peopleCount: Int?,
        val taxonomyVersion: Int?,
        val captureRevision: Long,
        val finalFrameId: String,
        /** 사진에서 읽을 만한 텍스트(메뉴판·안내문 등)를 감지했는가 — 텍스트 Q&A 안내의 트리거. */
        val hasText: Boolean = false,
        /** 감지된 텍스트가 무엇에 관한 것인지 짧은 요약 (예: "카페 메뉴판"). */
        val textTopic: String? = null,
        /** 감지된 텍스트 원문 — 후속 질문에 답할 근거로 그대로 인덱스에 저장한다. */
        val textContent: String? = null,
    )

    interface Callback {
        /** 생성 완료. 메인 스레드에서 호출된다. */
        fun onDone(metadata: Metadata)
        fun onDone(sessionId: String, metadata: Metadata) = onDone(metadata)

        /** 404·타임아웃 등으로 폴링을 접음. 검색 인덱스만 비는 것이므로 사용자에게 안내하지 않는다. */
        fun onGaveUp(reason: String)
        fun onGaveUp(sessionId: String, reason: String) = onGaveUp(reason)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val poller = SessionPoller<Metadata>(
        mainHandler = mainHandler,
        threadName = "SnapSight-MetadataPoll",
        totalTimeoutMs = TOTAL_TIMEOUT_MS,
        initialBackoffMs = DEFAULT_RETRY_MS,
        maxBackoffMs = MAX_RETRY_MS,
    )

    /** 기존 호출 호환. 새 코드는 revision 검증 overload를 사용한다. */
    fun pollMetadata(sessionId: String, callback: Callback): NetworkRequestHandle =
        pollMetadata(sessionId, expectedRevision = null, callback = callback)

    fun pollMetadata(
        sessionId: String,
        expectedRevision: Long?,
        callback: Callback,
    ): NetworkRequestHandle = poller.poll(
        sessionId = sessionId,
        fetch = { handle ->
            when (val decision = fetchDecision(sessionId, handle)) {
                is Decision.Done -> terminalIdentityError(
                    decision.metadata.captureRevision,
                    decision.metadata.finalFrameId,
                    expectedRevision,
                )?.let { PollOutcome.GaveUp(it) } ?: PollOutcome.Done(decision.metadata)
                is Decision.Failed -> PollOutcome.GaveUp(decision.reason)
                is Decision.NotFound -> PollOutcome.GaveUp("세션 없음(404) — 재시도 안 함")
                is Decision.Pending -> PollOutcome.Pending(decision.retryAfterMs)
            }
        },
        onTransientError = { error ->
            Log.w(TAG, "메타데이터 조회 실패, 백오프 후 재시도 [$sessionId]: ${error.message}")
        },
        onDone = { callback.onDone(sessionId, it) },
        onGaveUp = { callback.onGaveUp(sessionId, it) },
    )

    fun cancel(sessionId: String) = poller.cancel(sessionId)

    fun cancelAll() = poller.cancelAll()

    private fun fetchDecision(sessionId: String, handle: NetworkRequestHandle): Decision {
        val request = Request.Builder()
            .url("${baseUrl ?: BackendConfig.baseUrl}/api/capture/$sessionId/metadata")
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
        data class Done(val metadata: Metadata) : Decision()
        data class Pending(val retryAfterMs: Long) : Decision()
        data class Failed(val reason: String) : Decision()
        object NotFound : Decision()
    }

    companion object {
        private const val TAG = "MetadataClient"

        // 상위 모델의 상세 설명 생성 — 즉시 설명(45초)보다 여유 있게 잡는다
        internal const val TOTAL_TIMEOUT_MS = 120_000L
        internal const val DEFAULT_RETRY_MS = 3_000L
        internal const val MAX_RETRY_MS = 30_000L

        internal fun parseDecision(json: String): Decision {
            val obj = JSONObject(json)
            val status = obj.optString("status")
            if (status == "failed") {
                return Decision.Failed(
                    obj.optString("reason").takeIf { it.isNotBlank() }
                        ?: "capture pipeline failed"
                )
            }
            if (status in PENDING_POLL_STATUSES) {
                val seconds = obj.optDouble("retry_after_seconds", DEFAULT_RETRY_MS / 1000.0)
                return Decision.Pending(retryAfterMs = (seconds * 1000).toLong().coerceAtLeast(500L))
            }
            if (status != "done") {
                return Decision.Failed(
                    "unknown metadata status: ${status.ifBlank { "missing" }}"
                )
            }
            fun stringList(key: String): List<String> =
                obj.optJSONArray(key)?.let { array ->
                    List(array.length()) { array.getString(it) }
                } ?: emptyList()
            return Decision.Done(
                Metadata(
                    briefDescription = if (obj.isNull("brief_description")) null
                    else obj.optString("brief_description").takeIf { it.isNotBlank() },
                    // isNull 선확인 — org.json 은 JSON null 을 optString 에서 "null" 문자열로 돌려준다
                    longDescription = if (obj.isNull("long_description")) null
                    else obj.optString("long_description").takeIf { it.isNotBlank() },
                    labels = stringList("labels"),
                    customLabels = stringList("custom_labels"),
                    peopleCount = if (obj.isNull("people_count")) null else obj.optInt("people_count"),
                    taxonomyVersion = if (obj.isNull("taxonomy_version")) null
                    else obj.optInt("taxonomy_version"),
                    captureRevision = obj.optLong("capture_revision", -1L),
                    finalFrameId = obj.optString("final_frame_id"),
                    hasText = obj.optBoolean("has_text", false),
                    textTopic = if (obj.isNull("text_topic")) null
                    else obj.optString("text_topic").takeIf { it.isNotBlank() },
                    textContent = if (obj.isNull("text_content")) null
                    else obj.optString("text_content").takeIf { it.isNotBlank() },
                )
            )
        }
    }
}
