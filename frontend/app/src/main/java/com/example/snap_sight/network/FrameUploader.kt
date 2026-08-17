// 이 파일: 찍은 사진(대표 1장 + 후보 여러 장)을 백엔드 서버로 올리는 업로드 담당.
// 전송은 화면이 멈추지 않게 뒤(백그라운드)에서 처리하고 성공/실패만 알려준다.
// 서버 주소는 빌드 설정(BuildConfig)에서 받아온다.
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ⑤ → ④ 프레임 업로드 클라이언트.
 *
 * 백엔드 계약: POST {baseUrl}/api/capture/frames (multipart/form-data)
 *  - session_id: 텍스트
 *  - raw_text: 발화 원문 (필수 — 의도 없는 세션은 빈 문자열, ⑧ MLLM 프롬프트 컨텍스트)
 *  - representative_frame: 대표 컷 JPEG 1장
 *  - candidate_frames: 후보 JPEG N장
 *  - candidate_scores: 후보별 점수 dict 의 JSON 배열 (선택, 후보 순서와 매핑 — ⑦ 휴리스틱)
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
     * [representativeJpegProvider] / [candidateScoresProvider] 는 백그라운드 스레드에서
     * 호출되므로 MediaStore 읽기·이미지 디코딩 같은 무거운 작업을 넣어도 된다.
     *
     * @param rawText 발화 원문 — 의도 없는 세션(발화 스킵·인식 실패)은 빈 문자열
     * @param candidateScoresProvider 후보 순서대로의 블러 의심도(0..1). null 이면 점수 미전송.
     *        개수가 후보 수와 다르면 백엔드가 422 로 거부하므로 이때도 전송을 생략한다.
     */
    fun uploadCaptureFrames(
        sessionId: String,
        rawText: String,
        representativeJpegProvider: () -> ByteArray,
        candidates: List<RingFrameBuffer.Frame>,
        candidateScoresProvider: (() -> List<Float>)? = null,
        callback: Callback,
    ) {
        Thread({
            try {
                val representative = representativeJpegProvider()

                val bodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("session_id", sessionId)
                    .addFormDataPart("raw_text", rawText)
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

                val scores = candidateScoresProvider?.invoke()
                if (scores != null && scores.isNotEmpty() && scores.size == candidates.size) {
                    val scoresJson = JSONArray()
                    scores.forEach { scoresJson.put(JSONObject().put("blur_score", it.toDouble())) }
                    bodyBuilder.addFormDataPart("candidate_scores", scoresJson.toString())
                } else if (scores != null && scores.size != candidates.size) {
                    Log.w(TAG, "후보 점수 개수 불일치(${scores.size}/${candidates.size}) — 점수 전송 생략")
                }

                val request = Request.Builder()
                    .url("$baseUrl/api/capture/frames")
                    .post(bodyBuilder.build())
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // 422 등의 원인 파악을 위해 서버 detail 을 함께 남긴다
                        throw IllegalStateException(
                            "업로드 실패: HTTP ${response.code} — ${bodyText.take(300)}")
                    }
                    val json = JSONObject(bodyText)
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

        /**
         * 빌드 설정에서 주입되는 백엔드 주소 (기본: 에뮬레이터→호스트 10.0.2.2).
         * 실기기는 빌드 시 `-PBACKEND_BASE_URL=http://<PC LAN IP>:8000` 로 재정의한다.
         */
        const val DEFAULT_BASE_URL = com.example.snap_sight.BuildConfig.BACKEND_BASE_URL
    }
}
