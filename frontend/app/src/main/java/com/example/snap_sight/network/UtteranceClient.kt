package com.example.snap_sight.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ① 인식된 발화 텍스트를 백엔드로 보내 타겟 스펙을 받아오는 클라이언트.
 *
 * [SpeechToTextRecognizer][com.example.snap_sight.stt.SpeechToTextRecognizer]가
 * 온디바이스에서 인식한 텍스트를 그대로 전달한다 (오디오 업로드 없음).
 *
 * 백엔드 계약: POST {baseUrl}/api/session/utterance (application/json)
 *  - session_id: 문자열
 *  - raw_text: STT로 인식된 텍스트
 * 응답: ai/target_spec_schema.md 의 TargetSpec JSON
 *  (schemaVersion, sessionId, status, subjectType, objectLabel, subjectCount, framing,
 *   rawText, confidence, source) — 이 클라이언트는 ③ 판정 로직이 바로 쓸 핵심 필드만 옮겨 담는다.
 */
class UtteranceClient(
    private val baseUrl: String = FrameUploader.DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    class TargetSpecResult(
        val sessionId: String,
        val status: String,
        val subjectType: String,
        val objectLabel: String?,
        val subjectCount: Int?,
        val framing: String,
        val confidence: Double,
    )

    interface Callback {
        fun onSuccess(result: TargetSpecResult)
        fun onFailure(error: Throwable)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun sendUtterance(sessionId: String, rawText: String, callback: Callback) {
        Thread({
            try {
                val requestJson = JSONObject().apply {
                    put("session_id", sessionId)
                    put("raw_text", rawText)
                }
                val request = Request.Builder()
                    .url("$baseUrl/api/session/utterance")
                    .post(requestJson.toString().toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("타겟 스펙 요청 실패: HTTP ${response.code}")
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val result = TargetSpecResult(
                        sessionId = json.optString("sessionId", sessionId),
                        status = json.optString("status", ""),
                        subjectType = json.optString("subjectType", "person"),
                        objectLabel = if (json.isNull("objectLabel")) null else json.optString("objectLabel"),
                        subjectCount = if (json.isNull("subjectCount")) null else json.optInt("subjectCount"),
                        framing = json.optString("framing", "full_body"),
                        confidence = json.optDouble("confidence", 0.0),
                    )
                    mainHandler.post { callback.onSuccess(result) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "타겟 스펙 요청 실패 [$sessionId]", t)
                mainHandler.post { callback.onFailure(t) }
            }
        }, "SnapSight-UtteranceUpload").start()
    }

    companion object {
        private const val TAG = "UtteranceClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
