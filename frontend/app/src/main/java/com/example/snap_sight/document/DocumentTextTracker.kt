// 이 파일: 서류 모드(2026-08-30)의 관측기 — 분석 프레임에 ML Kit 텍스트 인식(한국어, 온디바이스)을
// 돌려 글자 줄 상자들을 [DocumentObservation] 으로 요약한다. 서류의 모서리 대신 글자 영역을 쓰는
// 이유: 흰 종이·흰 책상처럼 엣지가 사라져도 글자는 잡히고, 신분증·영수증·서류 어디에나 글자가
// 있다. 서류 세션에서만 돈다(다른 모드 비용 0). 사진은 기기 밖으로 나가지 않는다.
package com.example.snap_sight.document

import android.util.Log
import com.example.snap_sight.cv.CvFrame
import com.example.snap_sight.face.toBitmap
import com.example.snap_sight.ux.DocumentObservation
import com.example.snap_sight.ux.TextLineBox
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * 스레딩: [onFrame] 은 CV 분석 스레드에서 동기 호출된다([com.example.snap_sight.face.PersonFramingPoseTracker]
 * 와 같은 규약). 결과는 volatile [observation] 으로 노출해 메인 판정 루프가 읽는다.
 *
 * 비용: [enabled] 일 때만, [analysisIntervalMs] 간격으로만 1회 돈다. 640×480 프레임 전체를 넣는다
 * — 서류는 화면 대부분을 차지하는 게 목표라 크롭할 영역이 따로 없다.
 */
class DocumentTextTracker(
    private val analysisIntervalMs: Long = 350L,
) {
    /** 서류 세션일 때만 true. 메인 스레드에서 토글, 분석 스레드에서 읽음. */
    @Volatile
    var enabled: Boolean = false

    /** 최근 관측 — 글자가 없거나 인식 실패면 null. */
    @Volatile
    var observation: DocumentObservation? = null
        private set

    private var nextAnalysisAtMs = 0L

    private val recognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    fun reset() {
        observation = null
        nextAnalysisAtMs = 0L
    }

    /** 분석 프레임마다 호출 (CV 분석 스레드). 꺼져 있으면 즉시 리턴. */
    fun onFrame(frame: CvFrame) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now < nextAnalysisAtMs) return
        nextAnalysisAtMs = now + analysisIntervalMs

        val bitmap = frame.toBitmap() ?: return
        try {
            val text = try {
                Tasks.await(
                    recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                    DETECT_TIMEOUT_MS, TimeUnit.MILLISECONDS,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "서류 텍스트 인식 실패 — 이 프레임은 건너뜀", t)
                return
            }
            val width = frame.width.toFloat()
            val height = frame.height.toFloat()
            val lines = text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                if (line.text.isBlank()) return@mapNotNull null
                TextLineBox(
                    left = (box.left / width).coerceIn(0f, 1f),
                    top = (box.top / height).coerceIn(0f, 1f),
                    right = (box.right / width).coerceIn(0f, 1f),
                    bottom = (box.bottom / height).coerceIn(0f, 1f),
                    angleDegrees = line.angle,
                )
            }
            val union = DocumentObservation.fromLines(lines, glareFraction = 0f, nowMs = now)
            observation = union?.copy(
                glareFraction = glareFraction(frame, union.left, union.top, union.right, union.bottom),
            )
        } finally {
            bitmap.recycle()
        }
    }

    /** 서류 영역(정규화 상자) 안에서 거의 포화된 픽셀의 비율 — 서브샘플링이라 수 ms. */
    private fun glareFraction(frame: CvFrame, left: Float, top: Float, right: Float, bottom: Float): Float {
        val x0 = (left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val x1 = (right * frame.width).toInt().coerceIn(x0 + 1, frame.width)
        val y0 = (top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val y1 = (bottom * frame.height).toInt().coerceIn(y0 + 1, frame.height)
        var bright = 0
        var total = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val idx = (y * frame.width + x) * 3
                val r = frame.rgb[idx].toInt() and 0xFF
                val g = frame.rgb[idx + 1].toInt() and 0xFF
                val b = frame.rgb[idx + 2].toInt() and 0xFF
                if (((r + g + g + b) shr 2) >= GLARE_LUMA_THRESHOLD) bright++
                total++
                x += SAMPLE_STRIDE
            }
            y += SAMPLE_STRIDE
        }
        return if (total == 0) 0f else bright.toFloat() / total
    }

    private companion object {
        const val TAG = "DocumentText"
        const val DETECT_TIMEOUT_MS = 1_500L
        const val SAMPLE_STRIDE = 7
        /** 이 밝기(0..255) 이상이면 포화(반사)로 센다. */
        const val GLARE_LUMA_THRESHOLD = 245
    }
}
