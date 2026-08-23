// 이 파일: 찍은 사진(대표 1장 + 후보 여러 장)을 백엔드 서버로 올리는 업로드 담당.
// 전송은 화면이 멈추지 않게 뒤(백그라운드)에서 처리하고 성공/실패만 알려준다.
// 서버 주소는 빌드 설정(BuildConfig)에서 받아온다.
package com.example.snap_sight.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.snap_sight.camera.RingFrameBuffer
import com.example.snap_sight.cv.BoundingBox
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * ⑤ → ④ 프레임 업로드 클라이언트.
 *
 * 백엔드 계약: POST {baseUrl}/api/capture/frames (multipart/form-data)
 *  - session_id: 텍스트
 *  - raw_text: 발화 원문 (필수 — 의도 없는 세션은 빈 문자열, ⑧ MLLM 프롬프트 컨텍스트)
 *  - representative_frame: 대표 컷 JPEG 1장
 *  - candidate_frames: 후보 JPEG N장
 *  - candidate_scores: 후보별 회전값 + 선택 점수 dict의 JSON 배열 (후보 순서와 매핑)
 * 응답: { session_id, received_candidate_count, status }  (backend/api/capture.py 참고)
 *
 * 업로드는 자체 백그라운드 스레드에서 수행하고 콜백은 메인 스레드로 돌려준다.
 */
class FrameUploader(
    // null이면 요청 시점에 BackendConfig.baseUrl을 읽는다 — 설정에서 서버 주소를 바꿔도 즉시 반영
    private val baseUrl: String? = null,
    private val client: OkHttpClient = SnapSightHttp.client(
        connectSeconds = 5,
        writeSeconds = 30,
        readSeconds = 30,
    ),
) {

    class UploadResult(
        val sessionId: String,
        val receivedCandidateCount: Int,
        val status: String,
        val captureRevision: Long,
    )

    /**
     * 등록 이름 대신 한 촬영 세션 안에서만 의미가 있는 불투명 참조를 전송한다.
     * 서버에는 [subjectRef]·kind·bbox만 가며 실제 이름을 받을 필드가 아예 없다.
     */
    data class KnownSubject(
        val subjectRef: String,
        val kind: String,
        val bbox: BoundingBox?,
        /**
         * 이 대상이 세션 발화 의도("민수 찍어줘")의 타겟이면 true. 서버 MLLM 이 설명을
         * 이 대상 중심으로 서술하는 근거가 된다 — 이름이 아니라 표시 1비트만 나간다.
         */
        val isIntentTarget: Boolean = false,
        /**
         * 기기에 이 참조의 이름 매핑이 있으면 true (기본). false 면 서버는 토큰을 문장에
         * 쓰지 말고 "요청한 촬영 대상"으로 다뤄야 한다 — 치환할 이름이 없어 토큰이
         * 낭독에 그대로 노출되는 것을 막는다 (2026-08-23, 미등록 의도 대상 지원).
         */
        val hasLocalName: Boolean = true,
    ) {
        init {
            require(OPAQUE_REF.matches(subjectRef)) {
                "subjectRef must be an opaque local_* identifier"
            }
            require(kind == "person" || kind == "object")
        }
    }

    interface Callback {
        fun onSuccess(result: UploadResult)
        fun onSuccess(sessionId: String, result: UploadResult) = onSuccess(result)
        fun onFailure(error: Throwable)
        fun onFailure(sessionId: String, error: Throwable) = onFailure(error)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val uploads = SessionRequestRegistry()

    /**
     * 대표 컷과 후보 프레임을 업로드한다.
     * [representativeJpegProvider] / [candidateScoresProvider] 는 백그라운드 스레드에서
     * 호출되므로 MediaStore 읽기·이미지 디코딩 같은 무거운 작업을 넣어도 된다.
     *
     * @param rawText 발화 원문 — 의도 없는 세션(발화 스킵·인식 실패)은 빈 문자열
     * @param candidateScoresProvider 후보 순서대로의 블러 의심도(0..1). null 이면 점수 미전송.
     *        개수가 후보 수와 다르면 백엔드가 422 로 거부하므로 이때도 전송을 생략한다.
     * @param customLabels 사용자 커스텀 라벨 이름 목록 — LLM 이 이 사진에 해당하는 것을
     *        자동 부착 후보로 삼는다 (기능 3-B). 인물 이름은 넣지 않는다 (프라이버시 원칙).
     * @param detectedObjects 셔터 시점 온디바이스 탐지 라벨 — 메타데이터 프롬프트 참고 정보
     */
    fun uploadCaptureFrames(
        sessionId: String,
        rawText: String,
        representativeJpegProvider: () -> ByteArray,
        candidates: List<RingFrameBuffer.Frame>,
        candidateScoresProvider: (() -> List<Float>)? = null,
        /** 후보 순서대로의 눈감음 의심도(0..1, null=판정 불가) — MLLM tie-breaker (2026-08-23). */
        candidateEyesClosedProvider: (() -> List<Float?>)? = null,
        customLabels: List<String> = emptyList(),
        detectedObjects: List<String> = emptyList(),
        knownSubjects: List<KnownSubject> = emptyList(),
        callback: Callback,
    ): NetworkRequestHandle {
        val handle = uploads.replace(sessionId)
        val worker = Thread({
            var terminalPosted = false
            try {
                if (handle.isCancelled) return@Thread
                val representative = representativeJpegProvider()
                if (handle.isCancelled) return@Thread

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

                val suppliedScores = candidateScoresProvider?.invoke()
                val scores = suppliedScores?.takeIf { it.size == candidates.size }
                if (suppliedScores != null && suppliedScores.size != candidates.size) {
                    Log.w(
                        TAG,
                        "후보 점수 개수 불일치(${suppliedScores.size}/${candidates.size}) — blur 점수만 생략",
                    )
                }
                val eyesScores = candidateEyesClosedProvider?.invoke()
                    ?.takeIf { it.size == candidates.size }
                if (candidates.isNotEmpty()) {
                    bodyBuilder.addFormDataPart(
                        "candidate_scores",
                        buildCandidateScoresJson(candidates, scores, eyesScores),
                    )
                }

                // 검색용 메타데이터 재료 (기능 3-B) — 없으면 빈 배열 그대로 보낸다
                if (customLabels.isNotEmpty()) {
                    bodyBuilder.addFormDataPart(
                        "custom_labels", JSONArray(customLabels).toString())
                }
                if (detectedObjects.isNotEmpty()) {
                    bodyBuilder.addFormDataPart(
                        "detected_objects", JSONArray(detectedObjects).toString())
                }
                if (knownSubjects.isNotEmpty()) {
                    bodyBuilder.addFormDataPart("known_subjects", buildKnownSubjectsJson(knownSubjects))
                }

                val request = Request.Builder()
                    .url("${baseUrl ?: BackendConfig.baseUrl}/api/capture/frames")
                    .post(bodyBuilder.build())
                    .build()

                val result = client.executeCancellable(handle, request) { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // 422 등의 원인 파악을 위해 서버 detail 을 함께 남긴다
                        throw IllegalStateException(
                            "업로드 실패: HTTP ${response.code} — ${bodyText.take(300)}")
                    }
                    val json = JSONObject(bodyText)
                    val serverRevision = json.optLong("capture_revision", -1L)
                    check(serverRevision >= 1L) {
                        "업로드 응답의 capture_revision이 없음/잘못됨"
                    }
                    UploadResult(
                        sessionId = json.optString("session_id", sessionId),
                        receivedCandidateCount = json.optInt("received_candidate_count", -1),
                        status = json.optString("status", ""),
                        captureRevision = serverRevision,
                    )
                }
                terminalPosted = true
                mainHandler.post {
                    if (uploads.finish(sessionId, handle)) callback.onSuccess(sessionId, result)
                }
            } catch (t: Throwable) {
                if (handle.isCancelled) return@Thread
                Log.e(TAG, "프레임 업로드 실패 [$sessionId]", t)
                terminalPosted = true
                mainHandler.post {
                    if (uploads.finish(sessionId, handle)) callback.onFailure(sessionId, t)
                }
            } finally {
                handle.clearWorker(Thread.currentThread())
                if (!terminalPosted) uploads.remove(sessionId, handle)
            }
        }, "SnapSight-FrameUpload-${sessionId.takeLast(8)}")
        if (handle.attachWorker(worker)) worker.start()
        return handle
    }

    fun cancel(sessionId: String) = uploads.cancel(sessionId)

    fun cancelAll() = uploads.cancelAll()

    companion object {
        private const val TAG = "FrameUploader"
        private val JPEG = "image/jpeg".toMediaType()
        private val OPAQUE_REF = Regex("local_[A-Za-z0-9_-]{1,74}")

        internal fun buildCandidateScoresJson(
            candidates: List<RingFrameBuffer.Frame>,
            scores: List<Float>?,
            eyesClosedScores: List<Float?>? = null,
        ): String {
            require(scores == null || scores.size == candidates.size)
            require(eyesClosedScores == null || eyesClosedScores.size == candidates.size)
            return JSONArray().apply {
                candidates.forEachIndexed { index, frame ->
                    require(frame.rotationDegrees == 0 || frame.rotationDegrees == 90 ||
                        frame.rotationDegrees == 180 || frame.rotationDegrees == 270) {
                        "candidate rotation must be 0/90/180/270: ${frame.rotationDegrees}"
                    }
                    put(JSONObject().apply {
                        put("rotation_degrees", frame.rotationDegrees)
                        scores?.get(index)?.let { put("blur_score", it.toDouble()) }
                        // null = 얼굴 없음/판정 불가 — 필드를 생략해 MLLM 프롬프트에서 빠진다
                        eyesClosedScores?.get(index)?.let { put("eyes_closed_score", it.toDouble()) }
                    })
                }
            }.toString()
        }

        internal fun buildKnownSubjectsJson(subjects: List<KnownSubject>): String =
            JSONArray().apply {
                subjects.forEach { subject ->
                    put(JSONObject().apply {
                        put("subject_ref", subject.subjectRef)
                        put("kind", subject.kind)
                        if (subject.isIntentTarget) put("intent_target", true)
                        if (!subject.hasLocalName) put("named", false)
                        subject.bbox?.let { box ->
                            put("bbox", JSONObject().apply {
                                put("x_min", box.xMin.toDouble())
                                put("y_min", box.yMin.toDouble())
                                put("x_max", box.xMax.toDouble())
                                put("y_max", box.yMax.toDouble())
                            })
                        }
                    })
                }
            }.toString()

        /**
         * 변형별 빌드 설정에서 주입되는 주소. debug 실기기는 `-PBACKEND_BASE_URL=http://<LAN IP>:8000`,
         * release는 `-PSNAPSIGHT_RELEASE_BACKEND_BASE_URL=https://<host>`로 재정의한다.
         */
        const val DEFAULT_BASE_URL = com.example.snap_sight.BuildConfig.BACKEND_BASE_URL
    }
}
