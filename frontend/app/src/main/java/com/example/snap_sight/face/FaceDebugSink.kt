// 이 파일: 얼굴·사물 인식의 "무엇을 보고 어떻게 판정했는지"를 파일로 남기는 디버그 싱크 (2026-08-22).
// 등록 때 저장된 크롭과 식별 시도 때의 크롭·점수를 눈으로 확인하려는 개발용이며, 디버그 빌드에서만
// 연결한다 (MainActivity). 프라이버시 방침(임베딩·이름은 기기 밖으로 안 나감)은 그대로 — 이 파일도
// 기기 안 앱 전용 폴더에만 쓴다.
package com.example.snap_sight.face

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface FaceDebugSink {
    /** 등록 스캔에서 임베딩까지 성공한 크롭 1장. [kind] = "face" | "object". */
    fun onEnrollSample(kind: String, name: String, crop: Bitmap)

    /** 식별 시도 1회 — 비교한 크롭과 인물별 점수(높은 순), 최종 판정(null = 미확인). */
    fun onIdentifyAttempt(kind: String, crop: Bitmap, ranking: List<Pair<String, Float>>, decided: String?)

    /** person bbox 안에서 얼굴을 못 찾은 경우 — 그 영역 크롭. 얼굴 크기 미달·각도 문제를 확인용. */
    fun onNoFace(kind: String, regionCrop: Bitmap) {}
}

/**
 * 앱 전용 외부 저장소(`Android/data/<패키지>/files/face_debug/`)에 JPEG 로 남긴다 — 루트 없이
 * 폰의 파일 앱이나 PC(USB/MTP)로 바로 볼 수 있다. 외부 저장소가 없으면 내부 files 디렉터리.
 *
 * 경로: face_debug/<kind>/enroll/<이름>/<시각>.jpg, face_debug/<kind>/identify/<시각>_<판정>_<1위이름>_<점수>.jpg,
 *       face_debug/<kind>/noface/<시각>.jpg
 */
class FileFaceDebugSink(context: Context) : FaceDebugSink {

    private val root: File = (context.getExternalFilesDir(null) ?: context.filesDir).resolve("face_debug")
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    init {
        Log.i(TAG, "얼굴/사물 디버그 덤프 경로: ${root.absolutePath}")
    }

    override fun onEnrollSample(kind: String, name: String, crop: Bitmap) {
        write(root.resolve(kind).resolve("enroll").resolve(safe(name)), "${stamp.format(Date())}.jpg", crop)
    }

    override fun onIdentifyAttempt(kind: String, crop: Bitmap, ranking: List<Pair<String, Float>>, decided: String?) {
        val best = ranking.firstOrNull()
        val fileName = buildString {
            append(stamp.format(Date()))
            append('_').append(decided?.let(::safe) ?: "none")
            if (best != null) append('_').append(safe(best.first)).append('_').append("%.2f".format(best.second))
            append(".jpg")
        }
        write(root.resolve(kind).resolve("identify"), fileName, crop)
    }

    override fun onNoFace(kind: String, regionCrop: Bitmap) {
        write(root.resolve(kind).resolve("noface"), "${stamp.format(Date())}.jpg", regionCrop)
    }

    private fun write(dir: File, fileName: String, bitmap: Bitmap) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return
            dir.resolve(fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (t: Throwable) {
            Log.w(TAG, "디버그 크롭 저장 실패: ${dir.resolve(fileName)}", t)
        }
    }

    private fun safe(text: String): String = text.replace(Regex("[^0-9A-Za-z가-힣_-]"), "_").ifBlank { "_" }

    private companion object {
        const val TAG = "FaceDebugSink"
    }
}
