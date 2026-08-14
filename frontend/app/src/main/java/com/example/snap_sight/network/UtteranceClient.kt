package com.example.snap_sight.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.snap_sight.cv.TargetSpec
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
 * 응답: ai/target_spec_schema.md 의 TargetSpec JSON. 파싱은 [TargetSpec.fromJsonOrNull]에
 * 위임한다 — CV 쪽 검증 규칙(`ai/target_spec.py`)과 항상 같은 기준을 쓰기 위함이다.
 * 응답 body가 스키마를 어겨도(HTTP 자체는 성공) 예외 없이 [Callback.onSuccess]에 null이 온다.
 */
class UtteranceClient(
    private val baseUrl: String = FrameUploader.DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    interface Callback {
        /** [spec]은 응답이 스키마를 어겼을 때만 null — HTTP 자체 실패는 [onFailure]로 온다. */
        fun onSuccess(spec: TargetSpec?)
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
                    val body = response.body?.string().orEmpty()
                    val spec = TargetSpec.fromJsonOrNull(body) { error ->
                        Log.w(TAG, "타겟 스펙 응답 파싱 실패 [$sessionId]", error)
                    }
                    mainHandler.post { callback.onSuccess(spec) }
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
