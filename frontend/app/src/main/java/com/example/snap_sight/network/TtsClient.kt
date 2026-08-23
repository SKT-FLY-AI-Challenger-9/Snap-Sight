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
 * ① 재질문·에러 안내 등에 쓰이는 TTS 요청 클라이언트.
 *
 * 백엔드 계약: POST {baseUrl}/api/tts (application/json) { "text": "..." } → mp3 바이너리 응답
 * (backend/api/tts.py 참고). 백엔드가 ElevenLabs(Flash 모델, 실측 ~0.3초)를 대신 호출하므로
 * Android는 API 키를 몰라도 된다.
 */
class TtsClient(
    private val baseUrl: String? = null, // null = 요청 시점에 BackendConfig.baseUrl 사용
    private val client: OkHttpClient = SnapSightHttp.client(
        connectSeconds = 5,
        writeSeconds = 10,
        readSeconds = 10,
    ),
) {

    interface Callback {
        fun onSuccess(audioBytes: ByteArray)
        fun onFailure(error: Throwable)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun synthesize(text: String, callback: Callback) {
        Thread({
            try {
                val requestJson = JSONObject().apply { put("text", text) }
                val request = Request.Builder()
                    .url("${baseUrl ?: BackendConfig.baseUrl}/api/tts")
                    .post(requestJson.toString().toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("TTS 요청 실패: HTTP ${response.code}")
                    }
                    val audioBytes = response.body?.bytes() ?: ByteArray(0)
                    mainHandler.post { callback.onSuccess(audioBytes) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "TTS 요청 실패", t)
                mainHandler.post { callback.onFailure(t) }
            }
        }, "SnapSight-TtsRequest").start()
    }

    companion object {
        private const val TAG = "TtsClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
