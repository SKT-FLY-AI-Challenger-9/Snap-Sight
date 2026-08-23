package com.example.snap_sight.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.WorkerThread

/**
 * 서버가 선택한 canonical JPEG를 원본과 별도 MediaStore 항목으로 안전하게 저장한다.
 * 저해상도 후보가 기존 고해상도 대표 컷을 덮어쓰지 않도록 `_selected` 파일을 새로 만든다.
 */
class CanonicalFrameStore(context: Context) {
    private val resolver = context.contentResolver

    /** I/O를 수행하므로 백그라운드 스레드에서 호출한다. 실패하면 삽입한 URI를 반드시 삭제한다. */
    @WorkerThread
    fun save(sessionId: String, jpeg: ByteArray): Uri {
        require(SESSION_ID.matches(sessionId)) { "invalid sessionId" }
        require(jpeg.size >= 3 && jpeg[0] == 0xff.toByte() &&
            jpeg[1] == 0xd8.toByte() && jpeg[2] == 0xff.toByte()) {
            "canonical frame is not JPEG"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayNameFor(sessionId))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SnapSight")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = requireNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ) { "MediaStore insert returned null" }

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(jpeg)
                output.flush()
            } ?: error("MediaStore output stream could not be opened")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val published = resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
                check(published == 1) { "MediaStore pending item could not be published" }
            }
            return uri
        } catch (t: Throwable) {
            // Q+에서는 IS_PENDING 상태라 사용자에게 보이지 않고, pre-Q도 가능한 즉시 정리한다.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    companion object {
        private val SESSION_ID = Regex(
            "[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"
        )

        internal fun displayNameFor(sessionId: String): String =
            "SnapSight_${sessionId}_selected.jpg"
    }
}
