// 이 파일: 서류 모드(2026-08-30)의 관측값 — 텍스트 인식 결과(줄 상자들)를 "서류가 프레임 어디에
// 얼마나 크게, 얼마나 기울어져, 얼마나 비스듬히 있는지"로 요약한 순수 데이터. android.* 의존이
// 없어 [DocumentGuide] 와 함께 JVM 단위 테스트한다. 실제 인식은
// [com.example.snap_sight.document.DocumentTextTracker] 가 ML Kit 으로 한다.
package com.example.snap_sight.ux

import kotlin.math.abs
import kotlin.math.sqrt

/** 정규화 좌표(0..1)의 한 점. */
data class DocPoint(val x: Float, val y: Float)

/**
 * 서류 한 변의 직선 (2026-08-31, 외곽 v2) — 변과 나란한 프레임 축의 정규화 0/1 지점에서의
 * 위치 값. 위/아래 변이면 x=0/1 에서의 y, 왼/오른 변이면 y=0/1 에서의 x.
 */
data class DocLine(val at0: Float, val at1: Float) {
    fun at(t: Float): Float = at0 + (at1 - at0) * t
    val mid: Float get() = at(0.5f)
}

/**
 * 서류 모서리 4점 (2026-08-31) — 네 변 직선의 교점. 원근이 있으면 사다리꼴이 된다.
 * 수렴비가 1에서 멀수록 카메라가 그 축으로 서류 면에서 젖혀져 있다:
 * [verticalConvergence] < 1 → 위가 멀다(윗변이 짧음), [horizontalConvergence] < 1 → 왼쪽이 멀다.
 */
data class DocumentQuad(
    val tl: DocPoint,
    val tr: DocPoint,
    val br: DocPoint,
    val bl: DocPoint,
) {
    val topWidth: Float get() = distance(tl, tr)
    val bottomWidth: Float get() = distance(bl, br)
    val leftHeight: Float get() = distance(tl, bl)
    val rightHeight: Float get() = distance(tr, br)
    val verticalConvergence: Float get() = topWidth / bottomWidth.coerceAtLeast(1e-4f)
    val horizontalConvergence: Float get() = leftHeight / rightHeight.coerceAtLeast(1e-4f)

    companion object {
        /** 변 최소 길이(정규화) — 이보다 작으면 교점 노이즈로 본다. */
        const val MIN_SIDE = 0.05f

        /** 모서리가 프레임에서 이 이상 벗어나면 피팅 실패로 본다. */
        const val MAX_OVERSHOOT = 0.25f

        /**
         * 네 변 직선 → 모서리. 교점이 발산하거나(기울기 곱 ≈ 1), 변이 너무 짧거나, 모서리가
         * 프레임을 크게 벗어나거나, 순서가 뒤집혀 있으면 null.
         */
        fun from(left: DocLine, top: DocLine, right: DocLine, bottom: DocLine): DocumentQuad? {
            val tl = corner(left, top) ?: return null
            val tr = corner(right, top) ?: return null
            val br = corner(right, bottom) ?: return null
            val bl = corner(left, bottom) ?: return null
            for (p in listOf(tl, tr, br, bl)) {
                if (!p.x.isFinite() || !p.y.isFinite()) return null
                if (p.x < -MAX_OVERSHOOT || p.x > 1f + MAX_OVERSHOOT) return null
                if (p.y < -MAX_OVERSHOOT || p.y > 1f + MAX_OVERSHOOT) return null
            }
            if (tr.x - tl.x < MIN_SIDE || br.x - bl.x < MIN_SIDE) return null
            if (bl.y - tl.y < MIN_SIDE || br.y - tr.y < MIN_SIDE) return null
            return DocumentQuad(tl, tr, br, bl)
        }

        /** 세로 변(x = v(y))과 가로 변(y = h(x))의 교점. */
        private fun corner(v: DocLine, h: DocLine): DocPoint? {
            val hSlope = h.at1 - h.at0
            val vSlope = v.at1 - v.at0
            val denom = 1f - hSlope * vSlope
            if (abs(denom) < 1e-3f) return null
            val x = (v.at0 + vSlope * h.at0) / denom
            val y = h.at0 + hSlope * x
            return DocPoint(x, y)
        }

        private fun distance(a: DocPoint, b: DocPoint): Float {
            val dx = b.x - a.x
            val dy = b.y - a.y
            return sqrt(dx * dx + dy * dy)
        }
    }
}

/** 텍스트 한 줄의 프레임 정규화 상자(0..1)와 회전각(도, 이미지 기준). */
data class TextLineBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val angleDegrees: Float,
) {
    val height: Float get() = bottom - top
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * 서류 관측 1건. 서류의 모서리는 못 보므로(엣지 검출 없음) **글자 줄들의 합집합 상자**를
 * 서류 대용으로 쓴다 — 흰 종이·흰 책상처럼 엣지가 사라지는 상황에서도 동작한다는 게 장점.
 *
 * @property heightGradient 위쪽 줄들과 아래쪽 줄들의 글자 높이 차 (아래 − 위) / 평균. 카메라가
 *   서류 면에 수직이면 0, 폰 윗부분이 서류에서 젖혀져 있으면(윗줄이 멀어 작게 보임) 양수.
 *   줄이 4개 미만이면 잴 수 없어 0.
 * @property angleDegrees 줄 회전각의 중앙값(도). 0 이면 글자가 가로로 반듯. 부호는 ML Kit
 *   규약을 그대로 두고 소비자([DocumentGuide]/[com.example.snap_sight.camera.HorizonStraightener])
 *   가 해석한다.
 * @property glareFraction 서류 영역 안에서 거의 포화된(반사) 픽셀 비율 0..1.
 * @property edgeLeft..edgeBottom 그 변이 배경 대비 엣지 탐색([com.example.snap_sight.document.DocumentEdgeFinder])
 *   으로 확정됐는가. false 면 글자 여백 기반 — 판정 로직은 같고 정밀도만 다르다. 오버레이가
 *   확정 변은 실선, 폴백 변은 점선으로 그린다 (2026-08-31).
 */
data class DocumentObservation(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val lineCount: Int,
    val angleDegrees: Float,
    val heightGradient: Float,
    val glareFraction: Float,
    val atMs: Long,
    val edgeLeft: Boolean = false,
    val edgeTop: Boolean = false,
    val edgeRight: Boolean = false,
    val edgeBottom: Boolean = false,
    /** 네 변이 모두 잡혔을 때의 모서리 4점 — 기울임(원근) 판정·오버레이·촬영 후 보정 입력. */
    val corners: DocumentQuad? = null,
) {
    /** 엣지로 확정된 변의 수 (0..4) — 로그·튜닝용. */
    val edgeSides: Int get() = listOf(edgeLeft, edgeTop, edgeRight, edgeBottom).count { it }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    /** 프레임 대비 크기 척도 — 길이에 비례(√면적). [SubjectMotionDetector] 입력용. */
    val scale: Float get() = sqrt(area.coerceAtLeast(0f))

    companion object {
        /** 위/아래 줄 그룹 비교에 필요한 최소 줄 수 — 그 미만이면 [heightGradient] 는 0. */
        const val MIN_LINES_FOR_GRADIENT = 4

        /**
         * 줄 상자들 → 관측. 비어 있으면 null. 좌표는 0..1 로 클램프한다.
         * [glareFraction] 은 호출자가 프레임 픽셀로 따로 계산해 넘긴다(여기는 픽셀을 모른다).
         */
        fun fromLines(lines: List<TextLineBox>, glareFraction: Float, nowMs: Long): DocumentObservation? {
            if (lines.isEmpty()) return null
            val left = lines.minOf { it.left }.coerceIn(0f, 1f)
            val top = lines.minOf { it.top }.coerceIn(0f, 1f)
            val right = lines.maxOf { it.right }.coerceIn(0f, 1f)
            val bottom = lines.maxOf { it.bottom }.coerceIn(0f, 1f)
            return DocumentObservation(
                left = left, top = top, right = right, bottom = bottom,
                lineCount = lines.size,
                angleDegrees = medianAngle(lines),
                heightGradient = heightGradient(lines),
                glareFraction = glareFraction.coerceIn(0f, 1f),
                atMs = nowMs,
            )
        }

        /** 회전각 중앙값 — 한두 줄의 오검출이 평균을 끌고 가지 않게. */
        internal fun medianAngle(lines: List<TextLineBox>): Float {
            val sorted = lines.map { it.angleDegrees }.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
        }

        /**
         * 위쪽 1/3 줄들과 아래쪽 1/3 줄들의 평균 글자 높이 차이(아래 − 위)를 평균 높이로 나눈 값.
         * 같은 서류라도 먼 쪽 글자가 작게 찍히므로, 이 값이 카메라가 서류 면에서 얼마나
         * 위아래로 젖혀져 있는지의 대용이다. 줄이 적으면([MIN_LINES_FOR_GRADIENT] 미만) 0.
         */
        internal fun heightGradient(lines: List<TextLineBox>): Float {
            if (lines.size < MIN_LINES_FOR_GRADIENT) return 0f
            val byY = lines.sortedBy { it.centerY }
            val groupSize = (byY.size / 3).coerceAtLeast(1)
            val topHeight = byY.take(groupSize).map { it.height }.average().toFloat()
            val bottomHeight = byY.takeLast(groupSize).map { it.height }.average().toFloat()
            val mean = (topHeight + bottomHeight) / 2f
            if (mean <= 1e-6f) return 0f
            val gradient = (bottomHeight - topHeight) / mean
            return if (abs(gradient) < 1e-6f) 0f else gradient
        }
    }
}
