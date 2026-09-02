package com.example.snap_sight.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream

/** 선택이 끝난 정확한 capture revision의 canonical JPEG를 내려받는다. */
class FinalFrameClient(
    private val baseUrl: String? = null,
    private val client: OkHttpClient = SnapSightHttp.client(connectSeconds = 5, readSeconds = 30),
) {

    data class FinalFrame(
        val sessionId: String,
        val captureRevision: Long,
        val finalFrameId: String,
        val jpeg: ByteArray,
    )

    interface Callback {
        fun onSuccess(frame: FinalFrame)
        fun onFailure(error: Throwable)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloads = SessionRequestRegistry()

    /**
     * GET /final-frame에 revision을 반드시 넣고, 응답 헤더의 revision도 같은지 확인한다.
     * 같은 sessionId로 새 다운로드를 시작하면 이전 Call/콜백은 취소된다.
     */
    fun download(
        sessionId: String,
        captureRevision: Long,
        expectedFinalFrameId: String,
        callback: Callback,
    ): NetworkRequestHandle {
        require(captureRevision >= 1L)
        require(expectedFinalFrameId.isNotBlank())
        val handle = downloads.replace(sessionId)
        val worker = Thread({
            var terminalPosted = false
            try {
                val request = Request.Builder()
                    .url(
                        "${baseUrl ?: BackendConfig.baseUrl}/api/capture/$sessionId/final-frame" +
                            "?capture_revision=$captureRevision"
                    )
                    .get()
                    .header("Cache-Control", "no-cache")
                    .build()
                val frame = client.executeCancellable(handle, request) { response ->
                    if (!response.isSuccessful) {
                        val detail = response.body?.string().orEmpty().take(300)
                        throw IllegalStateException(
                            "최종 프레임 다운로드 실패: HTTP ${response.code} — $detail"
                        )
                    }
                    val contentType = response.body?.contentType()?.let { "${it.type}/${it.subtype}" }
                    check(contentType == "image/jpeg") {
                        "최종 프레임 Content-Type이 image/jpeg가 아님: $contentType"
                    }
                    val jpeg = readBounded(response.body)
                    val descriptor = validateFinalFrame(
                        expectedRevision = captureRevision,
                        expectedFinalFrameId = expectedFinalFrameId,
                        revisionHeader = response.header(HEADER_CAPTURE_REVISION),
                        frameIdHeader = response.header(HEADER_FINAL_FRAME_ID),
                        contentType = contentType,
                        jpeg = jpeg,
                    )
                    FinalFrame(
                        sessionId = sessionId,
                        captureRevision = descriptor.captureRevision,
                        finalFrameId = descriptor.finalFrameId,
                        jpeg = jpeg,
                    )
                }
                terminalPosted = true
                mainHandler.post {
                    if (downloads.finish(sessionId, handle)) callback.onSuccess(frame)
                }
            } catch (t: Throwable) {
                if (handle.isCancelled) return@Thread
                Log.e(TAG, "최종 프레임 다운로드 실패 [$sessionId r$captureRevision]", t)
                terminalPosted = true
                mainHandler.post {
                    if (downloads.finish(sessionId, handle)) callback.onFailure(t)
                }
            } finally {
                handle.clearWorker(Thread.currentThread())
                if (!terminalPosted) downloads.remove(sessionId, handle)
            }
        }, "SnapSight-FinalFrame-${sessionId.takeLast(8)}")
        if (handle.attachWorker(worker)) worker.start()
        return handle
    }

    fun cancel(sessionId: String) = downloads.cancel(sessionId)

    fun cancelAll() = downloads.cancelAll()

    private fun readBounded(body: ResponseBody?): ByteArray {
        val actual = requireNotNull(body) { "최종 프레임 응답 body가 없음" }
        val declared = actual.contentLength()
        require(declared < 0L || declared <= MAX_FINAL_FRAME_BYTES) {
            "최종 프레임이 너무 큼: $declared bytes"
        }
        val initialSize = declared.takeIf { it in 1L..MAX_FINAL_FRAME_BYTES }
            ?.toInt() ?: DEFAULT_BUFFER_BYTES
        return actual.byteStream().use { input ->
            val output = ByteArrayOutputStream(initialSize)
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_FINAL_FRAME_BYTES) { "최종 프레임이 제한을 초과함" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    companion object {
        private const val TAG = "FinalFrameClient"
        private const val HEADER_CAPTURE_REVISION = "X-Capture-Revision"
        private const val HEADER_FINAL_FRAME_ID = "X-Final-Frame-Id"
        private const val MAX_FINAL_FRAME_BYTES = 20L * 1024L * 1024L
        private const val DEFAULT_BUFFER_BYTES = 256 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024

        internal data class Descriptor(val captureRevision: Long, val finalFrameId: String)

        internal fun validateFinalFrame(
            expectedRevision: Long,
            expectedFinalFrameId: String,
            revisionHeader: String?,
            frameIdHeader: String?,
            contentType: String?,
            jpeg: ByteArray,
        ): Descriptor {
            val actualRevision = revisionHeader?.toLongOrNull()
                ?: throw IllegalStateException("최종 프레임 revision 헤더가 없음/잘못됨")
            check(actualRevision == expectedRevision) {
                "최종 프레임 revision 불일치: expected=$expectedRevision actual=$actualRevision"
            }
            val frameId = frameIdHeader?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("최종 프레임 ID 헤더가 없음")
            check(frameId == expectedFinalFrameId) {
                "최종 프레임 ID 불일치: expected=$expectedFinalFrameId actual=$frameId"
            }
            check(contentType == "image/jpeg") {
                "최종 프레임 Content-Type이 image/jpeg가 아님: $contentType"
            }
            check(jpeg.size >= 3 && jpeg[0] == 0xff.toByte() &&
                jpeg[1] == 0xd8.toByte() && jpeg[2] == 0xff.toByte()) {
                "최종 프레임 응답이 JPEG가 아님"
            }
            return Descriptor(actualRevision, frameId)
        }
    }
}
