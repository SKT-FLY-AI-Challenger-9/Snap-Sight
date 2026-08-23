// 이 파일: 등록 사물(이름 + YOLO 라벨 + 임베딩들)의 기기 로컬 저장소 (사물 등록, 2026-08-22).
// FaceRegistry 와 같은 구조지만 **일부러 별도 DB 파일로 분리**했다 — 사물 임베더를 나중에
// 전용 모델로 교체할 때(현재는 로컬 사물 외형 임베더 사용) 얼굴 데이터와 마이그레이션이 얽히지
// 않게 하기 위함이다. 저장되는 이름·임베딩은 기기 밖으로 나가지 않는다 (FaceRegistry 와 동일 방침).
package com.example.snap_sight.face

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 사물별 임베딩 저장소. 등록 사물 2~3개 × 개당 수십 벡터 규모라 전수 로드·비교로 충분하다.
 * `yoloLabel` 은 등록 당시 탐지된 YOLO 클래스 — 매칭 시 같은 클래스의 track 하고만 비교해
 * 오인식을 줄인다 (예: "내 텀블러"를 의자에 붙이지 않게).
 * 모든 메서드는 백그라운드/분석 스레드에서 호출할 것.
 */
class ObjectRegistry(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE objects (
                name TEXT PRIMARY KEY,
                yolo_label TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE object_embeddings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                object_name TEXT NOT NULL,
                vector BLOB NOT NULL,
                model_id TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE object_embeddings ADD COLUMN model_id TEXT NOT NULL DEFAULT 'legacy_face_v1'"
            )
        }
    }

    /** 사물 등록 (이미 있으면 무시 — 같은 이름으로 임베딩이 누적되고 라벨은 첫 등록값 유지). */
    fun registerObject(name: String, yoloLabel: String) {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("yolo_label", yoloLabel.trim().lowercase())
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("objects", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun addEmbeddings(
        name: String,
        yoloLabel: String,
        modelId: String,
        embeddings: List<FloatArray>,
    ) {
        if (embeddings.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            registerObject(name, yoloLabel)
            val createdAt = System.currentTimeMillis()
            embeddings.forEach { embedding ->
                val values = ContentValues().apply {
                    put("object_name", name.trim())
                    put("vector", FaceRegistry.encode(embedding))
                    put("model_id", modelId)
                    put("created_at", createdAt)
                }
                db.insertOrThrow("object_embeddings", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 사물 삭제 — 임베딩까지 함께 지운다. */
    fun deleteObject(name: String) {
        writableDatabase.delete("object_embeddings", "object_name = ?", arrayOf(name.trim()))
        writableDatabase.delete("objects", "name = ?", arrayOf(name.trim()))
    }

    fun objectNames(): List<String> =
        readableDatabase.query("objects", arrayOf("name"), null, null, null, null, "created_at ASC")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    /** 사물 이름 → 등록 당시 YOLO 라벨 (매칭 시 같은 클래스로 제한하는 데 쓴다). */
    fun labels(): Map<String, String> =
        readableDatabase.query("objects", arrayOf("name", "yolo_label"), null, null, null, null, null)
            .use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
            }

    /** 매칭용 갤러리 전체 로드 (사물 이름 → 임베딩 목록). */
    fun gallery(modelId: String): Map<String, List<FloatArray>> =
        readableDatabase.query(
            "object_embeddings", arrayOf("object_name", "vector"), "model_id = ?",
            arrayOf(modelId), null, null, null,
        ).use { cursor ->
            val result = LinkedHashMap<String, MutableList<FloatArray>>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val vector = FaceRegistry.decode(cursor.getBlob(1)) ?: continue
                result.getOrPut(name) { ArrayList() }.add(vector)
            }
            result
        }

    private companion object {
        const val DB_NAME = "snap_sight_objects.db"
        const val DB_VERSION = 2
    }
}
