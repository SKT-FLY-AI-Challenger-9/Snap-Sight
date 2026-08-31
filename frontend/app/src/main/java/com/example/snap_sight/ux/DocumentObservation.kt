// 이 파일: 서류 모드(2026-08-30)의 관측값 — 텍스트 인식 결과(줄 상자들)를 "서류가 프레임 어디에
// 얼마나 크게, 얼마나 기울어져, 얼마나 비스듬히 있는지"로 요약한 순수 데이터. android.* 의존이
// 없어 [DocumentGuide] 와 함께 JVM 단위 테스트한다. 실제 인식은
// [com.example.snap_sight.document.DocumentTextTracker] 가 ML Kit 으로 한다.
package com.example.snap_sight.ux

import kotlin.math.abs
import kotlin.math.sqrt

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
) {
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
