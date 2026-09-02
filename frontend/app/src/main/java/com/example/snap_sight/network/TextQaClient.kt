// 이 파일: 사진에서 감지된 텍스트(메뉴판·안내문 등)에 대한 음성 질문을 백엔드에 물어보는
// 통신 담당 (사용자 요청 2026-08-26). 실패하면 조용히 null로 수렴한다 — 호출부가
// "잘 못 들었어요"류로 안내한다.
package com.example.snap_sight.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 백엔드 계약: POST {baseUrl}/api/text/ask (application/json)
 *  - text: 사진에서 감지된 텍스트 원문
 *  - question: 사용자 음성 질문
 * 응답: {"answer": String|null}
 */
class TextQaClient(
    private val baseUrl: String? = null, // null = 요청 시점에 BackendConfig.baseUrl 사용
    private val client: OkHttpClient = SnapSightHttp.client(
        connectSeconds = 3,
        writeSeconds = 5,
        readSeconds = 15,
    ),
) {

    fun interface Callback {
        /** [answer]가 null이면 답을 찾지 못했거나 요청이 실패한 것. 메인 스레드에서 호출. */
        fun onAnswered(answer: String?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun ask(text: String, question: String, callback: Callback) {
        Thread({
            val answer = try {
                val requestJson = JSONObject().apply {
                    put("text", text)
                    put("question", question)
                }
                val request = Request.Builder()
                    .url("${baseUrl ?: BackendConfig.baseUrl}/api/text/ask")
                    .post(requestJson.toString().toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("텍스트 질문 응답 실패: HTTP ${response.code}")
                    }
                    val body = JSONObject(response.body?.string().orEmpty())
                    if (body.isNull("answer")) null
                    else body.optString("answer").takeIf { it.isNotBlank() }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "텍스트 질문 응답 요청 실패", t)
                null
            }
            mainHandler.post { callback.onAnswered(answer) }
        }, "SnapSight-TextQa").start()
    }

    companion object {
        private const val TAG = "TextQaClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
