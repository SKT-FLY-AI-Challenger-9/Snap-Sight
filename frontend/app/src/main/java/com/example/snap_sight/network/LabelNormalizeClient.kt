// 이 파일: 커스텀 라벨 등록 직전, 새 라벨이 기존 라벨의 동의어인지 백엔드에 물어보는 통신 담당
// ("내 개" vs "내 강아지" 병합 — 실사용 피드백 2026-08-22). 실패하면 조용히 "새 라벨"로 처리해
// 라벨 등록 흐름이 절대 막히지 않게 한다.
package com.example.snap_sight.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 백엔드 계약: POST {baseUrl}/api/labels/normalize (application/json)
 *  - label: 새로 등록하려는 라벨
 *  - existing_labels: 기존 커스텀 라벨 목록
 * 응답: {"action": "merge"|"new", "canonical_label": String|null}
 *
 * [Callback.onResolved]의 [canonical]이 null이 아니면 그 기존 라벨로 병합하라는 뜻이다.
 * HTTP 실패·응답 이상은 전부 null(새 라벨)로 수렴한다 — 오프라인에서도 등록은 돼야 한다.
 */
class LabelNormalizeClient(
    private val baseUrl: String? = null, // null = 요청 시점에 BackendConfig.baseUrl 사용
    private val client: OkHttpClient = SnapSightHttp.client(
        connectSeconds = 3,
        writeSeconds = 5,
        readSeconds = 8,
    ),
) {

    fun interface Callback {
        /** [canonical]이 null이면 새 라벨로 저장, 아니면 그 기존 라벨로 병합. 메인 스레드에서 호출. */
        fun onResolved(canonical: String?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun normalize(label: String, existingLabels: List<String>, callback: Callback) {
        Thread({
            val canonical = try {
                val requestJson = JSONObject().apply {
                    put("label", label)
                    put("existing_labels", JSONArray(existingLabels))
                }
                val request = Request.Builder()
                    .url("${baseUrl ?: BackendConfig.baseUrl}/api/labels/normalize")
                    .post(requestJson.toString().toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("라벨 병합 판정 실패: HTTP ${response.code}")
                    }
                    val body = JSONObject(response.body?.string().orEmpty())
                    body.optString("canonical_label")
                        .takeIf { body.optString("action") == "merge" && it.isNotBlank() }
                        // 백엔드도 검증하지만, 목록 밖 라벨로의 병합은 여기서도 걸러낸다
                        ?.takeIf { it in existingLabels }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "라벨 병합 판정 실패 — 새 라벨로 저장", t)
                null
            }
            mainHandler.post { callback.onResolved(canonical) }
        }, "SnapSight-LabelNormalize").start()
    }

    companion object {
        private const val TAG = "LabelNormalizeClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
