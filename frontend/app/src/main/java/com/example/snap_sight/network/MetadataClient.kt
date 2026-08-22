// 이 파일: 촬영 업로드 후 검색용 상세 메타데이터(상세 설명 + 라벨)를 서버에 물어보는 담당.
// 도착하면 로컬 사진 인덱스(PhotoIndexStore)에 기록돼 오프라인 검색·상세 낭독에 쓰인다 (기능 3-B).
package com.example.snap_sight.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    /** done 응답의 내용. 폐쇄형 계약 검증(사전 대조)은 서버가 이미 마쳤다. */
    data class Metadata(
        val longDescription: String?,
        val labels: List<String>,
        val customLabels: List<String>,
        val peopleCount: Int?,
        val taxonomyVersion: Int?,
    )

    interface Callback {
        /** 생성 완료. 메인 스레드에서 호출된다. */
        fun onDone(metadata: Metadata)

        /** 404·타임아웃 등으로 폴링을 접음. 검색 인덱스만 비는 것이므로 사용자에게 안내하지 않는다. */
        fun onGaveUp(reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun pollMetadata(sessionId: String, callback: Callback) {
        Thread({
            val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val decision = try {
                    fetchDecision(sessionId)
                } catch (t: Throwable) {
                    Log.w(TAG, "메타데이터 조회 실패, 재시도 [$sessionId]: ${t.message}")
                    Decision.Pending(DEFAULT_RETRY_MS)
                }
                when (decision) {
                    is Decision.Done -> {
                        mainHandler.post { callback.onDone(decision.metadata) }
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
        }, "SnapSight-MetadataPoll").start()
    }

    private fun fetchDecision(sessionId: String): Decision {
        val request = Request.Builder()
            .url("${baseUrl ?: BackendConfig.baseUrl}/api/capture/$sessionId/metadata")
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
        data class Done(val metadata: Metadata) : Decision()
        data class Pending(val retryAfterMs: Long) : Decision()
        object NotFound : Decision()
    }

    companion object {
        private const val TAG = "MetadataClient"

        // 상위 모델의 상세 설명 생성 — 즉시 설명(45초)보다 여유 있게 잡는다
        internal const val TOTAL_TIMEOUT_MS = 120_000L
        internal const val DEFAULT_RETRY_MS = 3_000L

        internal fun parseDecision(json: String): Decision {
            val obj = JSONObject(json)
            if (obj.optString("status") != "done") {
                val seconds = obj.optDouble("retry_after_seconds", DEFAULT_RETRY_MS / 1000.0)
                return Decision.Pending(retryAfterMs = (seconds * 1000).toLong().coerceAtLeast(500L))
            }
            fun stringList(key: String): List<String> =
                obj.optJSONArray(key)?.let { array ->
                    List(array.length()) { array.getString(it) }
                } ?: emptyList()
            return Decision.Done(
                Metadata(
                    // isNull 선확인 — org.json 은 JSON null 을 optString 에서 "null" 문자열로 돌려준다
                    longDescription = if (obj.isNull("long_description")) null
                    else obj.optString("long_description").takeIf { it.isNotBlank() },
                    labels = stringList("labels"),
                    customLabels = stringList("custom_labels"),
                    peopleCount = if (obj.isNull("people_count")) null else obj.optInt("people_count"),
                    taxonomyVersion = if (obj.isNull("taxonomy_version")) null
                    else obj.optInt("taxonomy_version"),
                )
            )
        }
    }
}
