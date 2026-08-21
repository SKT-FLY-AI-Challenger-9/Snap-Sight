// 이 파일: 등록 인물(이름 + 얼굴 임베딩들)의 기기 로컬 저장소 (기능 2).
// ⚠️ 프라이버시 원칙: 여기 저장되는 이름·임베딩은 어떤 경로로도 기기 밖으로 나가면 안 된다.
// 서버 업로드·로그에 섞지 말 것 (docs/feature-expansion-plan.md 기능 2 방침).
package com.example.snap_sight.face

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 인물별 임베딩 저장소. 등록 인원 2~3명 × 인당 수십 벡터 규모라 전수 로드·비교로 충분하다.
 * 모든 메서드는 백그라운드/분석 스레드에서 호출할 것.
 */
class FaceRegistry(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE people (
                name TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE face_embeddings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                person_name TEXT NOT NULL,
                vector BLOB NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** 인물 등록 (이미 있으면 무시 — 같은 이름으로 임베딩이 누적된다). */
    fun registerPerson(name: String) {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("people", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun addEmbedding(name: String, embedding: FloatArray) {
        registerPerson(name)
        val values = ContentValues().apply {
            put("person_name", name.trim())
            put("vector", encode(embedding))
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("face_embeddings", null, values)
    }

    /** 인물 삭제 — 임베딩까지 함께 지운다 (프라이버시: 삭제는 완전해야 한다). */
    fun deletePerson(name: String) {
        writableDatabase.delete("face_embeddings", "person_name = ?", arrayOf(name.trim()))
        writableDatabase.delete("people", "name = ?", arrayOf(name.trim()))
    }

    fun peopleNames(): List<String> =
        readableDatabase.query("people", arrayOf("name"), null, null, null, null, "created_at ASC")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    /** 매칭용 갤러리 전체 로드 (인물 → 임베딩 목록). */
    fun gallery(): Map<String, List<FloatArray>> =
        readableDatabase.query(
            "face_embeddings", arrayOf("person_name", "vector"), null, null, null, null, null,
        ).use { cursor ->
            val result = LinkedHashMap<String, MutableList<FloatArray>>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val vector = decode(cursor.getBlob(1)) ?: continue
                result.getOrPut(name) { ArrayList() }.add(vector)
            }
            result
        }

    fun embeddingCount(name: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM face_embeddings WHERE person_name = ?", arrayOf(name.trim()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    companion object {
        private const val DB_NAME = "snap_sight_faces.db"
        private const val DB_VERSION = 1

        internal fun encode(embedding: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            embedding.forEach { buffer.putFloat(it) }
            return buffer.array()
        }

        internal fun decode(bytes: ByteArray): FloatArray? {
            if (bytes.isEmpty() || bytes.size % 4 != 0) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buffer.getFloat() }
        }
    }
}
