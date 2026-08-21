// 이 파일: AI가 찾은 물체 위에 노란 라운드 박스를 그려주는 덧그림 (Make 시안 v31의 추적 상자).
// 시각 사용자·데모 참관자를 위한 보조 표시 — 실제 사용자(시각장애인)용 안내는 소리·진동으로 나간다.
package com.example.snap_sight.ux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.snap_sight.cv.TrackedObject

/**
 * ② CV 스트림(추적 객체)을 미리보기 위에 그리는 오버레이 — 시안의 노란 추적 상자.
 *
 * 좌표 매핑: bbox 는 정방향 프레임 기준 0..1 정규화(x_min..y_max)이고,
 * PreviewView 는 FIT_CENTER(CaptureScreen 참고) — 프레임 전체가 화면 안에 들어오도록 균등 축소·중앙 정렬
 * (남는 부분은 검은 띠) → 같은 변환을 적용해야 박스가 미리보기와 일치한다.
 *
 * @param frameAspect 정방향 분석 프레임의 가로/세로 비 (기본 480x640 세로 = 0.75)
 * @param mirrored    전면(셀카) 카메라 — 미리보기는 좌우 반전돼 보이지만 분석 프레임은
 *                    반전이 없으므로, 박스 x 좌표를 뒤집어야 미리보기와 일치한다
 */
@Composable
fun DetectionOverlay(
    objects: List<TrackedObject>,
    frameAspect: Float = 3f / 4f,
    mirrored: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (frameAspect <= 0f || objects.isEmpty()) return@Canvas

        // FIT_CENTER: 프레임 전체가 화면 안에 들어오도록 축소 후 중앙 정렬 (남는 부분은 레터박스)
        val viewAspect = size.width / size.height
        val shownWidth: Float
        val shownHeight: Float
        if (frameAspect > viewAspect) {
            shownWidth = size.width
            shownHeight = size.width / frameAspect
        } else {
            shownHeight = size.height
            shownWidth = size.height * frameAspect
        }
        val offsetX = (size.width - shownWidth) / 2f
        val offsetY = (size.height - shownHeight) / 2f

        val stroke = Stroke(width = 3.dp.toPx())
        val corner = CornerRadius(14.dp.toPx(), 14.dp.toPx())
        for (o in objects) {
            val xMin = if (mirrored) 1f - o.bbox.xMax else o.bbox.xMin
            val xMax = if (mirrored) 1f - o.bbox.xMin else o.bbox.xMax
            val left = offsetX + xMin * shownWidth
            val top = offsetY + o.bbox.yMin * shownHeight
            val right = offsetX + xMax * shownWidth
            val bottom = offsetY + o.bbox.yMax * shownHeight

            drawRoundRect(
                color = SnapPalette.Warning,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = corner,
                style = stroke,
            )
        }
    }
}
