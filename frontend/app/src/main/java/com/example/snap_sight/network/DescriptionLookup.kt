// 이 파일: 사진 찾기 화면(#78)이 세션 ID로 백엔드의 AI 한 줄 설명을 조회하는 담당.
// 한 번 받은 설명은 SharedPreferences에 캐시해 오프라인에서도 다시 보이게 한다.
package com.example.snap_sight.network

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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

    // 라벨 생성은 Haiku 호출이라 수십 초 걸릴 수 있어 조회용과 별도 타임아웃을 쓴다
    private val labelClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * 사진첩 카드용 라벨('장소·피사체')·설명 — [cacheKey]로 캐시하고, 없으면 썸네일을
     * 서버에 보내 생성한다. 실패·서버 불통이면 null (자리표시 유지). 백그라운드 스레드 전용.
     */
    fun labelForPhoto(cacheKey: String, thumbnail: Bitmap?): Pair<String, String>? {
        prefs.getString("label:$cacheKey", null)?.let { cached ->
            val newline = cached.indexOf('\n')
            if (newline > 0) return cached.take(newline) to cached.substring(newline + 1)
        }
        if (serverUnreachable || thumbnail == null) return null
        val result = fetchLabel(thumbnail) ?: return null
        prefs.edit().putString("label:$cacheKey", "${result.first}\n${result.second}").apply()
        return result
    }

    private fun fetchLabel(thumbnail: Bitmap): Pair<String, String>? {
        val buffer = ByteArrayOutputStream()
        thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, buffer)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "photo", "photo.jpg",
                buffer.toByteArray().toRequestBody("image/jpeg".toMediaType()),
            )
            .build()
        val request = Request.Builder().url("$baseUrl/api/photos/describe").post(body).build()
        return try {
            labelClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val obj = JSONObject(response.body?.string().orEmpty())
                val label = obj.optString("label").takeIf { it.isNotBlank() } ?: return null
                val description = obj.optString("description").takeIf { it.isNotBlank() } ?: return null
                label to description
            }
        } catch (t: Throwable) {
            Log.w(TAG, "사진 라벨링 실패 — 이번 로드는 캐시만 사용: ${t.message}")
            serverUnreachable = true
            null
        }
    }

    companion object {
        private const val TAG = "DescriptionLookup"
        private const val PREFS_NAME = "snap_sight_photo_descriptions"
    }
}
