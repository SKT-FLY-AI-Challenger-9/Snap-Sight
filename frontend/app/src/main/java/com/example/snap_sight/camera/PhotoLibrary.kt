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
    /** 파일명에 심긴 세션 ID — 로컬 사진 인덱스(검색·상세 낭독)와의 연결 고리. 옛 사진은 null. */
    val sessionId: String? = null,
    /** 촬영 시각 (MediaStore DATE_ADDED). 인덱스에 없는 옛 사진의 시간 검색 폴백. */
    val takenAtMs: Long = 0L,
    /**
     * 이 사진에 붙은 표시용 라벨 이름 통합(고정 사전 한글명 + 커스텀 + 인물 이름) —
     * 사진 찾기의 라벨 폴더 화면이 사용한다. 인덱스에 없는 옛 사진은 빈 집합.
     */
    val labels: Set<String> = emptySet(),
)

object PhotoLibrary {

    private const val TAG = "PhotoLibrary"
    // 동물 샘플 사진 7종 x 7장(49장) 데모 데이터가 다 보이도록 상향 (사용자 요청 2026-08-26)
    private const val MAX_PHOTOS = 60
    private const val MAX_SCAN_ROWS = MAX_PHOTOS * 4
    private val THUMBNAIL_SIZE = Size(256, 256)

    /**
     * Pictures/SnapSight의 사진을 최신순으로 최대 [MAX_PHOTOS]장 읽는다. 백그라운드 스레드에서 호출할 것.
     * [describe]는 파일명에 심긴 세션 ID로 AI 설명을 돌려준다 (없으면 null → 자리표시 문구).
     */
    fun loadRecentPhotos(
        context: Context,
        describe: (sessionId: String) -> String? = { null },
    ): List<GalleryPhoto> {
        data class MediaRow(val id: Long, val displayName: String?, val addedAtMs: Long)
        val rows = mutableListOf<MediaRow>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
        )
        // RELATIVE_PATH는 API 29+ 전용이라 파일명 접두사로 거른다 — 저장 규칙(SnapSight_*)과 한 쌍.
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.IS_PENDING}=0"
        } else {
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        }
        val selectionArgs = arrayOf("SnapSight_%")

        try {
            context.contentResolver.query(
                collection, projection, selection, selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext() && rows.size < MAX_SCAN_ROWS) {
                    rows += MediaRow(
                        id = cursor.getLong(idCol),
                        displayName = cursor.getString(nameCol),
                        addedAtMs = cursor.getLong(dateCol) * 1000L,
                    )
                }
            }
            return preferredPhotoIndices(rows.map { it.displayName })
                .take(MAX_PHOTOS)
                .map { rowIndex ->
                    val row = rows[rowIndex]
                    val addedAt = Date(row.addedAtMs)
                    val uri = ContentUris.withAppendedId(collection, row.id)
                    val sessionId = sessionIdFromDisplayName(row.displayName)
                    GalleryPhoto(
                            uri = uri,
                            thumbnail = loadThumbnail(context, uri),
                            title = titleFormat.format(addedAt) + " 촬영",
                            dateText = dateFormat.format(addedAt),
                            description = sessionId?.let(describe) ?: "설명을 준비 중이에요",
                            sessionId = sessionId,
                            takenAtMs = addedAt.time,
                    )
                }
        } catch (t: Throwable) {
            Log.w(TAG, "사진 목록 조회 실패", t)
        }
        return emptyList()
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

    /** 원본과 `..._selected.jpg` 모두 같은 session ID로 해석한다. */
    internal fun sessionIdFromDisplayName(displayName: String?): String? {
        val raw = displayName ?: return null
        if (!raw.startsWith("SnapSight_")) return null
        val stem = raw.removePrefix("SnapSight_").substringBeforeLast('.')
        val candidate = stem.removeSuffix(SELECTED_SUFFIX)
        // UUID는 현재 계약, s_*는 이미 저장된 옛 사진 호환.
        return candidate.takeIf { UUID_SESSION.matches(it) || it.startsWith("s_") }
    }

    internal fun isSelectedDisplayName(displayName: String?): Boolean {
        val stem = displayName?.removePrefix("SnapSight_")?.substringBeforeLast('.') ?: return false
        return stem.endsWith(SELECTED_SUFFIX) && sessionIdFromDisplayName(displayName) != null
    }

    /** 최신순 이름 목록에서 세션당 한 장만 남기되 selected가 있으면 원본보다 우선한다. */
    internal fun preferredPhotoIndices(displayNames: List<String?>): List<Int> {
        val result = mutableListOf<Int>()
        val resultPositionBySession = mutableMapOf<String, Int>()
        displayNames.forEachIndexed { index, name ->
            val session = sessionIdFromDisplayName(name)
            if (session == null) {
                result += index
                return@forEachIndexed
            }
            val existingPosition = resultPositionBySession[session]
            if (existingPosition == null) {
                resultPositionBySession[session] = result.size
                result += index
            } else {
                val existingIndex = result[existingPosition]
                if (!isSelectedDisplayName(displayNames[existingIndex]) && isSelectedDisplayName(name)) {
                    result[existingPosition] = index
                }
            }
        }
        return result
    }

    private val titleFormat = SimpleDateFormat("M월 d일 H시 m분", Locale.KOREAN)
    private val dateFormat = SimpleDateFormat("M월 d일", Locale.KOREAN)
    private const val SELECTED_SUFFIX = "_selected"
    private val UUID_SESSION = Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
}
