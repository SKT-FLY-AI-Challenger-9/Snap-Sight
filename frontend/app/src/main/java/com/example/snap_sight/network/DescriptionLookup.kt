// 이 파일: 사진 찾기 화면(#78)이 세션 ID로 백엔드의 AI 한 줄 설명을 조회하는 담당.
// 한 번 받은 설명은 SharedPreferences에 캐시해 오프라인에서도 다시 보이게 한다.
package com.example.snap_sight.network

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DescriptionLookup(
    context: Context,
    private val baseUrl: String = FrameUploader.DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build(),
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 서버가 안 떠 있으면 사진마다 타임아웃을 기다리게 되므로, 한 번 실패하면 이번 로드는 통째로 건너뛴다
    @Volatile
    private var serverUnreachable = false

    /** 새 목록 로드 시작 시 호출 — 이전 로드의 서버 불통 기억을 지운다. */
    fun beginBatch() {
        serverUnreachable = false
    }

    /** 캐시 우선 조회, 없으면 서버에서 받아와 캐시한다. 실패·미생성이면 null. 백그라운드 스레드 전용. */
    fun get(sessionId: String): String? {
        prefs.getString(sessionId, null)?.let { return it }
        if (serverUnreachable) return null
        val fetched = fetch(sessionId) ?: return null
        prefs.edit().putString(sessionId, fetched).apply()
        return fetched
    }

    private fun fetch(sessionId: String): String? {
        val request = Request.Builder()
            .url("$baseUrl/api/capture/$sessionId/description")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val obj = JSONObject(response.body?.string().orEmpty())
                if (obj.optString("status") != "done") return null
                obj.optString("description").takeIf { it.isNotBlank() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "설명 조회 실패 — 이번 로드는 캐시만 사용 [$sessionId]: ${t.message}")
            serverUnreachable = true
            null
        }
    }

    companion object {
        private const val TAG = "DescriptionLookup"
        private const val PREFS_NAME = "snap_sight_photo_descriptions"
    }
}
