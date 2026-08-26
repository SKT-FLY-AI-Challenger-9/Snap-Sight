// 이 파일: 사진 검색용 로컬 인덱스(SQLite)와 사용자 커스텀 라벨 사전의 저장 담당.
// 메타데이터 폴링이 성공하면 여기 기록되고, 이후 검색은 완전 오프라인으로 동작한다.
// Room 대신 순정 SQLiteOpenHelper — 빌드 의존성을 늘리지 않는다 (기능 3-B).
package com.example.snap_sight.search

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * `photo_index` 행은 세션 ID 로 사진(MediaStore 파일명에 심긴 것과 동일)과 연결된다.
 * 라벨은 auto(LLM 부착)/user(사용자 직접 부착)를 분리 저장한다 — 고정 사전 버전업
 * 재라벨링 때 auto 만 갱신하고 user 는 보존하기 위함 (docs/feature-expansion-plan.md).
 *
 * 모든 메서드는 백그라운드 스레드에서 호출할 것.
 */
class PhotoIndexStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE photo_index (
                session_id TEXT PRIMARY KEY,
                taken_at INTEGER NOT NULL,
                location_text TEXT,
                fixed_labels TEXT NOT NULL DEFAULT '[]',
                custom_auto TEXT NOT NULL DEFAULT '[]',
                custom_user TEXT NOT NULL DEFAULT '[]',
                people TEXT NOT NULL DEFAULT '[]',
                short_desc TEXT,
                long_desc TEXT,
                taxonomy_version INTEGER,
                has_text INTEGER NOT NULL DEFAULT 0,
                text_topic TEXT,
                text_content TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE custom_labels (
                name TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 인덱스는 재생성 가능한 캐시성 데이터라 최악의 경우 drop-recreate 도 허용되지만,
        // 컬럼 추가만으로 충분할 때는 기존 행(라벨·설명)을 지킨다.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE photo_index ADD COLUMN has_text INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE photo_index ADD COLUMN text_topic TEXT")
            db.execSQL("ALTER TABLE photo_index ADD COLUMN text_content TEXT")
        }
    }

    /** 촬영 직후 기본 행 생성 — 메타데이터 도착 전에도 시간 검색이 되게 한다. */
    fun insertCapture(sessionId: String, takenAtMs: Long, locationText: String?) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("taken_at", takenAtMs)
            put("location_text", locationText)
        }
        writableDatabase.insertWithOnConflict(
            "photo_index", null, values, SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** 서버 메타데이터 도착 시 갱신. 사용자 직접 라벨(custom_user)은 건드리지 않는다. */
    fun applyMetadata(
        sessionId: String,
        longDescription: String?,
        fixedLabels: List<String>,
        customAuto: List<String>,
        taxonomyVersion: Int?,
        shortDescription: String? = null,
        hasText: Boolean = false,
        textTopic: String? = null,
        textContent: String? = null,
    ) {
        val values = ContentValues().apply {
            put("long_desc", longDescription)
            put("fixed_labels", JSONArray(fixedLabels).toString())
            put("custom_auto", JSONArray(customAuto).toString())
            taxonomyVersion?.let { put("taxonomy_version", it) }
            shortDescription?.let { put("short_desc", it) }
            put("has_text", if (hasText) 1 else 0)
            put("text_topic", textTopic)
            put("text_content", textContent)
        }
        writableDatabase.update("photo_index", values, "session_id = ?", arrayOf(sessionId))
    }

    /** 즉시 낭독용 2문장 설명 저장 (검색 폴백·카드 표시에 재사용). */
    fun applyShortDescription(sessionId: String, shortDescription: String) {
        val values = ContentValues().apply { put("short_desc", shortDescription) }
        writableDatabase.update("photo_index", values, "session_id = ?", arrayOf(sessionId))
    }

    /** 온디바이스 인물 인식 결과 저장 (기능 2 연동) — 로컬 전용, 서버 안 거침. */
    fun applyPeople(sessionId: String, people: List<String>) {
        val values = ContentValues().apply { put("people", JSONArray(people).toString()) }
        writableDatabase.update("photo_index", values, "session_id = ?", arrayOf(sessionId))
    }

    /** "이 사진 '제주도 여행'으로 기억해줘" — 커스텀 라벨 등록 + 해당 사진에 직접 부착. */
    fun attachUserLabel(sessionId: String, labelName: String) {
        registerCustomLabel(labelName)
        val entry = entry(sessionId) ?: return
        val updated = entry.customUser + labelName
        val values = ContentValues().apply {
            put("custom_user", JSONArray(updated.toList()).toString())
        }
        writableDatabase.update("photo_index", values, "session_id = ?", arrayOf(sessionId))
    }

    /** 커스텀 라벨 사전 등록 (이미 있으면 무시). 이후 촬영 업로드에 함께 전송돼 자동 부착 대상이 된다. */
    fun registerCustomLabel(labelName: String) {
        val values = ContentValues().apply {
            put("name", labelName.trim())
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "custom_labels", null, values, SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun allCustomLabels(): List<String> =
        readableDatabase.query(
            "custom_labels", arrayOf("name"), null, null, null, null, "created_at ASC",
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    fun entry(sessionId: String): PhotoIndexEntry? =
        readableDatabase.query(
            "photo_index", null, "session_id = ?", arrayOf(sessionId), null, null, null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toEntry() else null }

    /** 전체 인덱스 (최신 촬영 순). 수백 장 규모까지는 전체 로드 후 메모리 필터로 충분하다. */
    fun allEntries(): List<PhotoIndexEntry> =
        readableDatabase.query(
            "photo_index", null, null, null, null, null, "taken_at DESC",
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toEntry()) }
        }

    private fun Cursor.toEntry(): PhotoIndexEntry = PhotoIndexEntry(
        sessionId = getString(getColumnIndexOrThrow("session_id")),
        takenAtMs = getLong(getColumnIndexOrThrow("taken_at")),
        locationText = getStringOrNull("location_text"),
        fixedLabels = readJsonSet("fixed_labels"),
        customAuto = readJsonSet("custom_auto"),
        customUser = readJsonSet("custom_user"),
        people = readJsonSet("people"),
        shortDescription = getStringOrNull("short_desc"),
        longDescription = getStringOrNull("long_desc"),
        taxonomyVersion = getColumnIndexOrThrow("taxonomy_version").let {
            if (isNull(it)) null else getInt(it)
        },
        hasText = getInt(getColumnIndexOrThrow("has_text")) != 0,
        textTopic = getStringOrNull("text_topic"),
        textContent = getStringOrNull("text_content"),
    )

    private fun Cursor.getStringOrNull(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.readJsonSet(column: String): Set<String> {
        val raw = getStringOrNull(column) ?: return emptySet()
        return try {
            val array = JSONArray(raw)
            buildSet { for (index in 0 until array.length()) add(array.getString(index)) }
        } catch (t: Throwable) {
            emptySet()
        }
    }

    private companion object {
        const val DB_NAME = "snap_sight_photo_index.db"
        // v2: has_text/text_topic/text_content 컬럼 추가 (텍스트 감지 Q&A, 2026-08-26)
        const val DB_VERSION = 2
    }
}
