// 이 파일: 앱이 찍어 MediaStore(Pictures/SnapSight)에 저장한 사진을 최신순으로 읽어오는 담당.
// 사진 찾기 화면(#78)의 데이터 소스 — 썸네일까지 만들어 UI가 바로 그릴 수 있게 준다.
package com.example.snap_sight.camera

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 사진 찾기 화면의 한 장 — 썸네일·제목·날짜·설명(1차는 자리표시) 묶음. */
data class GalleryPhoto(
    val uri: Uri,
    val thumbnail: Bitmap?,
    val title: String,
    val dateText: String,
    val description: String,
)

object PhotoLibrary {

    private const val TAG = "PhotoLibrary"
    private const val MAX_PHOTOS = 30
    private val THUMBNAIL_SIZE = Size(256, 256)

    /**
     * Pictures/SnapSight의 사진을 최신순으로 최대 [MAX_PHOTOS]장 읽는다. 백그라운드 스레드에서 호출할 것.
     * [describe]는 파일명에 심긴 세션 ID로 AI 설명을 돌려준다 (없으면 null → 자리표시 문구).
     */
    fun loadRecentPhotos(
        context: Context,
        describe: (sessionId: String) -> String? = { null },
    ): List<GalleryPhoto> {
        val photos = mutableListOf<GalleryPhoto>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
        )
        // RELATIVE_PATH는 API 29+ 전용이라 파일명 접두사로 거른다 — 저장 규칙(SnapSight_*)과 한 쌍.
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("SnapSight_%")

        try {
            context.contentResolver.query(
                collection, projection, selection, selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext() && photos.size < MAX_PHOTOS) {
                    val id = cursor.getLong(idCol)
                    val addedAt = Date(cursor.getLong(dateCol) * 1000L)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val sessionId = sessionIdFromDisplayName(cursor.getString(nameCol))
                    photos.add(
                        GalleryPhoto(
                            uri = uri,
                            thumbnail = loadThumbnail(context, uri),
                            title = titleFormat.format(addedAt) + " 촬영",
                            dateText = dateFormat.format(addedAt),
                            description = sessionId?.let(describe) ?: "설명을 준비 중이에요",
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "사진 목록 조회 실패", t)
        }
        return photos
    }

    private fun loadThumbnail(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, THUMBNAIL_SIZE, null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "썸네일 로드 실패: $uri", t)
        null
    }

    /** "SnapSight_s_20260819_145301.jpg" → "s_20260819_145301". 세션 ID가 안 심긴 옛 사진은 null. */
    internal fun sessionIdFromDisplayName(displayName: String?): String? {
        val stem = displayName?.removePrefix("SnapSight_")?.substringBeforeLast('.') ?: return null
        return stem.takeIf { it.startsWith("s_") }
    }

    private val titleFormat = SimpleDateFormat("M월 d일 H시 m분", Locale.KOREAN)
    private val dateFormat = SimpleDateFormat("M월 d일", Locale.KOREAN)
}
