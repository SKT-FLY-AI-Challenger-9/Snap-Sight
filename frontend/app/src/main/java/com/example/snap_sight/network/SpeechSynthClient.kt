// 이 파일: 동적 문장(촬영 요약·사진 설명)을 백엔드 SKT A.X TTS 프록시로 합성해 mp3 를 받아온다.
// 고정 스크립트 문장은 assets 프리캐싱이 담당하고, 이 클라이언트는 카탈로그 밖 문장 전용이다.
// 실패는 전부 null — 호출부(GuidanceFeedback)가 내장 TTS 로 폴백한다.
package com.example.snap_sight.network

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class SpeechSynthClient {

    // 짧은 타임아웃 — 합성이 느리면 내장 TTS 폴백이 낫다 (안내가 밀리는 것보다)
    private val client = SnapSightHttp.client(connectSeconds = 2, readSeconds = 5)

    /** 문장을 프리셋 보이스로 합성한 mp3 바이트. 실패·비 mp3 응답이면 null. */
    fun fetch(text: String, voice: String): ByteArray? {
        val body = JSONObject()
            .put("text", text)
            .put("voice", voice)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/api/tts/skt")
            .post(body)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "동적 합성 실패: HTTP ${response.code}")
                    return null
                }
                val bytes = response.body?.bytes() ?: return null
                // mp3 매직바이트 검증 — 프록시/게이트웨이 오류 페이지 방어
                if (bytes.size < 3 || !isMp3(bytes)) return null
                bytes
            }
        }.getOrElse {
            Log.w(TAG, "동적 합성 요청 실패", it)
            null
        }
    }

    private fun isMp3(bytes: ByteArray): Boolean {
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) return true
        return bytes[0] == 0xFF.toByte() &&
            (bytes[1].toInt() and 0xE0) == 0xE0
    }

    private companion object {
        const val TAG = "SpeechSynthClient"
    }
}
