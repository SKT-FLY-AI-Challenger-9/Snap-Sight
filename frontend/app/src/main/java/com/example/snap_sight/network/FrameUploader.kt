package com.example.snap_sight.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.snap_sight.camera.RingFrameBuffer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ⑤ → ④ 프레임 업로드 클라이언트.
 *
 * 백엔드 계약: POST {baseUrl}/api/capture/frames (multipart/form-data)
 *  - session_id: 텍스트
 *  - representative_frame: 대표 컷 JPEG 1장
 *  - candidate_frames: 후보 JPEG N장
 * 응답: { session_id, received_candidate_count, status }  (backend/api/capture.py 참고)
 *
 * 업로드는 자체 백그라운드 스레드에서 수행하고 콜백은 메인 스레드로 돌려준다.
 */
class FrameUploader(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    class UploadResult(val sessionId: String, val receivedCandidateCount: Int, val status: String)

    interface Callback {
        fun onSuccess(result: UploadResult)
        fun onFailure(error: Throwable)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 대표 컷과 후보 프레임을 업로드한다.
     * [representativeJpegProvider] 는 백그라운드 스레드에서 호출되므로
     * MediaStore Uri 읽기 같은 IO 를 넣어도 된다.
     */
    fun uploadCaptureFrames(
        sessionId: String,
        representativeJpegProvider: () -> ByteArray,
        candidates: List<RingFrameBuffer.Frame>,
        callback: Callback,
    ) {
        Thread({
            try {
                val representative = representativeJpegProvider()

                val bodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("session_id", sessionId)
                    .addFormDataPart(
                        "representative_frame",
                        "representative.jpg",
                        representative.toRequestBody(JPEG),
                    )
                candidates.forEachIndexed { index, frame ->
                    bodyBuilder.addFormDataPart(
                        "candidate_frames",
                        "candidate_%02d_t%d_r%d.jpg".format(index, frame.timestampMs, frame.rotationDegrees),
                        frame.jpeg.toRequestBody(JPEG),
                    )
                }

                val request = Request.Builder()
                    .url("$baseUrl/api/capture/frames")
                    .post(bodyBuilder.build())
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("업로드 실패: HTTP ${response.code}")
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val result = UploadResult(
                        sessionId = json.optString("session_id", sessionId),
                        receivedCandidateCount = json.optInt("received_candidate_count", -1),
                        status = json.optString("status", ""),
                    )
                    mainHandler.post { callback.onSuccess(result) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "프레임 업로드 실패 [$sessionId]", t)
                mainHandler.post { callback.onFailure(t) }
            }
        }, "SnapSight-FrameUpload").start()
    }

    companion object {
        private const val TAG = "FrameUploader"
        private val JPEG = "image/jpeg".toMediaType()

        /** 에뮬레이터에서 호스트 PC 의 FastAPI 개발 서버. 실기기는 PC LAN IP 로 교체. */
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"
    }
}
