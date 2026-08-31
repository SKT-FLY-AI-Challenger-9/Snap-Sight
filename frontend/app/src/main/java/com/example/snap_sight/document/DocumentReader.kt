// 이 파일: 서류 모드 1단계(2026-08-30) — 저장된 촬영본(원본 해상도)에 ML Kit 한국어 텍스트 인식을
// 돌려 [DocumentText] 를 만든다. 라이브 프레임(640×480)은 프레이밍용이라 글자가 뭉개지므로 본문은
// 반드시 여기서 다시 읽는다. 온디바이스 전용 — 사진도 본문도 기기 밖으로 나가지 않는다.
package com.example.snap_sight.document

import android.graphics.Bitmap
import android.util.Log
import com.example.snap_sight.ux.DocumentText
import com.example.snap_sight.ux.RecognizedLine
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.concurrent.TimeUnit

object DocumentReader {

    private val recognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    /**
     * upright 비트맵 → 본문. 긴 변이 [MAX_SIDE] 를 넘으면 축소해서 넣는다(인식 품질은 2000px 근처에서
     * 충분하고, 12MP 원본을 그대로 넣으면 수 초 걸린다). 실패하면 null. 백그라운드에서만 호출할 것.
     */
    fun recognize(bitmap: Bitmap): DocumentText? {
        val scale = (MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val input = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        try {
            val text = try {
                Tasks.await(
                    recognizer.process(InputImage.fromBitmap(input, 0)),
                    RECOGNIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "서류 본문 인식 실패", t)
                return null
            }
            val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                RecognizedLine(
                    top = box.top.toFloat(),
                    left = box.left.toFloat(),
                    height = box.height().toFloat(),
                    text = line.text,
                )
            }
            Log.i(TAG, "서류 본문 인식: ${lines.size}줄")
            return DocumentText.fromRecognized(lines)
        } finally {
            if (input !== bitmap) input.recycle()
        }
    }

    private const val TAG = "DocumentReader"
    private const val MAX_SIDE = 2_048
    private const val RECOGNIZE_TIMEOUT_MS = 8_000L
}
