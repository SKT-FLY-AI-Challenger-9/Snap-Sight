// 이 파일: 촬영 업로드 후 "방금 뭐가 찍혔는지" 한 줄 설명을 서버에 물어보는 담당.
// 무거운 비교 결과(CaptureResultClient)와 병렬로 돌아 설명을 먼저 들려주기 위한 클라이언트다 (#76).
package com.example.snap_sight.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 대표 컷 한 줄 설명 폴링 클라이언트.
 *
 * 백엔드 계약은 result 폴링과 동일 규약:
 *  - GET {baseUrl}/api/capture/{session_id}/description
 *  - 404 → 재시도 안 함 / pending → retry_after_seconds 뒤 재조회 / done → description 사용
 *  - description이 null이면 생성 실패 — 안내 없이 조용히 넘어간다
 */
class PhotoDescriptionClient(
    private val baseUrl: String = FrameUploader.DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    interface Callback {
        /** 설명 생성 완료. 메인 스레드에서 호출된다. null이면 생성 실패였다는 뜻. */
        fun onDone(description: String?)

        /** 404·타임아웃 등으로 폴링을 접음. 촬영 흐름에 영향 없어야 하므로 안내하지 않는다. */
        fun onGaveUp(reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun pollDescription(sessionId: String, callback: Callback) {
        Thread({
            val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val decision = try {
                    fetchDecision(sessionId)
                } catch (t: Throwable) {
                    Log.w(TAG, "설명 조회 실패, 재시도 [$sessionId]: ${t.message}")
                    Decision.Pending(DEFAULT_RETRY_MS)
                }
                when (decision) {
                    is Decision.Done -> {
                        mainHandler.post { callback.onDone(decision.description) }
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
        }, "SnapSight-DescriptionPoll").start()
    }

    private fun fetchDecision(sessionId: String): Decision {
        val request = Request.Builder()
            .url("$baseUrl/api/capture/$sessionId/description")
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
        data class Done(val description: String?) : Decision()
        data class Pending(val retryAfterMs: Long) : Decision()
        object NotFound : Decision()
    }

    companion object {
        private const val TAG = "PhotoDescriptionClient"

        // Haiku 1장 설명 실측 ~26초(API 느린 시간대) + 여유
        internal const val TOTAL_TIMEOUT_MS = 45_000L
        internal const val DEFAULT_RETRY_MS = 1_000L

        internal fun parseDecision(json: String): Decision {
            val obj = JSONObject(json)
            return if (obj.optString("status") == "done") {
                Decision.Done(obj.optString("description").takeIf { it.isNotBlank() })
            } else {
                val seconds = obj.optDouble("retry_after_seconds", DEFAULT_RETRY_MS / 1000.0)
                Decision.Pending(retryAfterMs = (seconds * 1000).toLong().coerceAtLeast(500L))
            }
        }
    }
}
