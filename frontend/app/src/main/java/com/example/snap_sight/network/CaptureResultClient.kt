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
import java.util.concurrent.TimeUnit

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
    private val baseUrl: String = FrameUploader.DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    class ComparisonResult(val improved: Boolean, val reason: String?)

    interface Callback {
        /** 비교 완료. 메인 스레드에서 호출된다. */
        fun onDone(result: ComparisonResult)

        /** 404·타임아웃 등으로 폴링을 접음. 촬영 흐름에 영향 없어야 하므로 안내는 하지 않는 것을 권장. */
        fun onGaveUp(reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun pollResult(sessionId: String, callback: Callback) {
        Thread({
            val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val decision = try {
                    fetchDecision(sessionId)
                } catch (t: Throwable) {
                    // 일시적 네트워크 오류는 타임아웃까지 재시도
                    Log.w(TAG, "결과 조회 실패, 재시도 [$sessionId]: ${t.message}")
                    Decision.Pending(DEFAULT_RETRY_MS)
                }
                when (decision) {
                    is Decision.Done -> {
                        mainHandler.post {
                            callback.onDone(ComparisonResult(decision.improved, decision.reason))
                        }
                        return@Thread
                    }
                    is Decision.NotFound -> {
                        mainHandler.post { callback.onGaveUp("세션 없음(404) — 재시도 안 함") }
                        return@Thread
                    }
                    is Decision.Pending -> Thread.sleep(decision.retryAfterMs)
                }
            }
            mainHandler.post { callback.onGaveUp("타임아웃(${TOTAL_TIMEOUT_MS / 1000}초)") }
        }, "SnapSight-ResultPoll").start()
    }

    private fun fetchDecision(sessionId: String): Decision {
        val request = Request.Builder()
            .url("$baseUrl/api/capture/$sessionId/result")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return Decision.NotFound
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} — ${body.take(200)}")
            }
            return parseDecision(body)
        }
    }

    /** 폴링 판정 (순수 로직, JVM 테스트 대상). */
    internal sealed class Decision {
        data class Done(val improved: Boolean, val reason: String?) : Decision()
        data class Pending(val retryAfterMs: Long) : Decision()
        object NotFound : Decision()
    }

    companion object {
        private const val TAG = "CaptureResultClient"

        // LLM 폴백 세션 실측 ~12초(#37) + 여유. 계약상 30초 미만으로 잡으면 안 된다.
        internal const val TOTAL_TIMEOUT_MS = 45_000L
        internal const val DEFAULT_RETRY_MS = 2_000L

        internal fun parseDecision(json: String): Decision {
            val obj = JSONObject(json)
            return if (obj.optString("status") == "done") {
                Decision.Done(
                    improved = obj.optBoolean("improved", false),
                    reason = obj.optString("reason").takeIf { it.isNotBlank() },
                )
            } else {
                val seconds = obj.optDouble("retry_after_seconds", DEFAULT_RETRY_MS / 1000.0)
                Decision.Pending(retryAfterMs = (seconds * 1000).toLong().coerceAtLeast(500L))
            }
        }
    }
}
