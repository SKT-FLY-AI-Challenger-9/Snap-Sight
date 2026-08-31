// 이 파일: 서류 모드(2026-08-30)의 관측기 — 분석 프레임에 ML Kit 텍스트 인식(한국어, 온디바이스)을
// 돌려 글자 줄 상자들을 [DocumentObservation] 으로 요약한다. 서류의 모서리 대신 글자 영역을 쓰는
// 이유: 흰 종이·흰 책상처럼 엣지가 사라져도 글자는 잡히고, 신분증·영수증·서류 어디에나 글자가
// 있다. 서류 세션에서만 돈다(다른 모드 비용 0). 사진은 기기 밖으로 나가지 않는다.
package com.example.snap_sight.document

import android.util.Log
import com.example.snap_sight.cv.CvFrame
import com.example.snap_sight.face.toBitmap
import com.example.snap_sight.ux.DocLine
import com.example.snap_sight.ux.DocumentObservation
import com.example.snap_sight.ux.DocumentQuad
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
        stickyLeft.atMs = 0L
        stickyTop.atMs = 0L
        stickyRight.atMs = 0L
        stickyBottom.atMs = 0L
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
            observation = union?.let { fuseWithEdges(frame, it) }
        } finally {
            bitmap.recycle()
        }
    }

    /** 변별 최근 확정 엣지 직선 — 프레임마다 잡혔다 풀렸다 하며 경계가 널뛰는 것을 막는 스티키 값. */
    private class StickyEdge(var value: DocLine = DocLine(0f, 0f), var atMs: Long = 0L)

    private val stickyLeft = StickyEdge()
    private val stickyTop = StickyEdge()
    private val stickyRight = StickyEdge()
    private val stickyBottom = StickyEdge()

    /**
     * 서류 외곽 v1 (2026-08-31): 글자 상자 바깥에서 배경 대비 엣지를 찾아 관측 경계를 실제 서류
     * 변으로 넓힌다 ([DocumentEdgeFinder]). 대비가 없어 못 찾은 변은 글자 여백 그대로(fail-open).
     * 반사 판정도 넓어진(실제 서류) 영역 기준으로 다시 잰다.
     *
     * 실기기(2026-08-31): 변이 프레임마다 독립적으로 잡혔다 풀렸다 해서 경계가 글자 여백과 실제
     * 엣지 사이를 널뛰었다 — 면적·중심이 튀며 안내 판정이 계속 리셋됐다. 그래서 한 번 확정된
     * 변은 [EDGE_STICKY_MS] 동안 유지한다(단, 여전히 현재 글자 상자 바깥에 있을 때만 — 줌·이동
     * 으로 낡아진 값이 글자 안쪽으로 파고들면 버린다).
     */
    private fun fuseWithEdges(frame: CvFrame, union: DocumentObservation): DocumentObservation {
        val lumaWidth = frame.width / LUMA_DOWNSAMPLE
        val lumaHeight = frame.height / LUMA_DOWNSAMPLE
        val edges = if (lumaWidth > 0 && lumaHeight > 0) {
            DocumentEdgeFinder.find(
                luma = downsampledLuma(frame, lumaWidth, lumaHeight),
                width = lumaWidth,
                height = lumaHeight,
                textLeft = union.left, textTop = union.top,
                textRight = union.right, textBottom = union.bottom,
            )
        } else {
            DocumentEdgeFinder.Edges()
        }
        val now = union.atMs
        val left = stickyOrFresh(edges.left, stickyLeft, now) { it.mid <= union.left }
        val top = stickyOrFresh(edges.top, stickyTop, now) { it.mid <= union.top }
        val right = stickyOrFresh(edges.right, stickyRight, now) { it.mid >= union.right }
        val bottom = stickyOrFresh(edges.bottom, stickyBottom, now) { it.mid >= union.bottom }
        // 네 변이 다 서면 교점 = 모서리 4점 — 기울임(원근) 판정·오버레이·촬영 후 보정의 입력
        val quad = if (left != null && top != null && right != null && bottom != null) {
            DocumentQuad.from(left = left, top = top, right = right, bottom = bottom)
        } else {
            null
        }
        val fused = union.copy(
            left = (quad?.let { minOf(it.tl.x, it.bl.x) } ?: left?.mid ?: union.left).coerceIn(0f, 1f),
            top = (quad?.let { minOf(it.tl.y, it.tr.y) } ?: top?.mid ?: union.top).coerceIn(0f, 1f),
            right = (quad?.let { maxOf(it.tr.x, it.br.x) } ?: right?.mid ?: union.right).coerceIn(0f, 1f),
            bottom = (quad?.let { maxOf(it.bl.y, it.br.y) } ?: bottom?.mid ?: union.bottom).coerceIn(0f, 1f),
            edgeLeft = left != null,
            edgeTop = top != null,
            edgeRight = right != null,
            edgeBottom = bottom != null,
            corners = quad,
        )
        if (fused.edgeSides > 0) {
            Log.i(
                TAG,
                "서류 외곽 융합: 엣지 ${fused.edgeSides}변 " +
                    "(L=${fused.edgeLeft} T=${fused.edgeTop} R=${fused.edgeRight} B=${fused.edgeBottom}, " +
                    "이번 프레임 ${edges.count}변" +
                    (quad?.let { ", 수렴 V=%.2f H=%.2f".format(it.verticalConvergence, it.horizontalConvergence) } ?: "") +
                    ")",
            )
        }
        return fused.copy(
            glareFraction = glareFraction(frame, fused.left, fused.top, fused.right, fused.bottom),
        )
    }

    /**
     * 이번 프레임에 변이 잡혔으면 그 값을 쓰고 스티키를 갱신, 안 잡혔으면 [EDGE_STICKY_MS] 안의
     * 최근 확정값을 재사용한다 — 단 [stillOutside] (현재 글자 상자 바깥)일 때만.
     */
    private inline fun stickyOrFresh(
        fresh: DocLine?,
        sticky: StickyEdge,
        nowMs: Long,
        stillOutside: (DocLine) -> Boolean,
    ): DocLine? {
        if (fresh != null) {
            sticky.value = fresh
            sticky.atMs = nowMs
            return fresh
        }
        if (sticky.atMs != 0L && nowMs - sticky.atMs <= EDGE_STICKY_MS && stillOutside(sticky.value)) {
            return sticky.value
        }
        return null
    }

    /** RGB 프레임 → [LUMA_DOWNSAMPLE] 배 축소 루마(0..255). 640×480 기준 320×240, 수 ms. */
    private fun downsampledLuma(frame: CvFrame, lumaWidth: Int, lumaHeight: Int): IntArray {
        val luma = IntArray(lumaWidth * lumaHeight)
        var index = 0
        for (y in 0 until lumaHeight) {
            val srcY = y * LUMA_DOWNSAMPLE
            for (x in 0 until lumaWidth) {
                val src = (srcY * frame.width + x * LUMA_DOWNSAMPLE) * 3
                val r = frame.rgb[src].toInt() and 0xFF
                val g = frame.rgb[src + 1].toInt() and 0xFF
                val b = frame.rgb[src + 2].toInt() and 0xFF
                luma[index++] = (r + g + g + b) shr 2
            }
        }
        return luma
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
        /** 엣지 탐색용 루마 축소 배수 — 640×480 → 320×240. */
        const val LUMA_DOWNSAMPLE = 2
        /** 확정된 변을 재확정 없이 유지하는 시간 — 검출 널뛰기로 경계·판정이 튀는 것 방지 (2026-08-31). */
        const val EDGE_STICKY_MS = 1_500L
        /** 이 밝기(0..255) 이상이면 포화(반사)로 센다. */
        const val GLARE_LUMA_THRESHOLD = 245
    }
}
