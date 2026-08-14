package com.example.snap_sight.ux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.snap_sight.cv.Detection

/**
 * 온디바이스 탐지 결과를 미리보기 위에 그리는 디버그 오버레이.
 * 데모·검증용 — 정식 화면(⑥)에서는 시각 요소가 아니라 음성·햅틱으로 안내한다.
 *
 * 좌표 매핑: [Detection] 은 정방향 프레임 기준 0..1 정규화 좌표이고,
 * PreviewView 기본 scaleType(FILL_CENTER)은 프레임을 균등 확대해 화면을 채우고
 * 넘치는 부분을 잘라낸다 → 같은 변환을 적용해야 박스가 미리보기와 일치한다.
 *
 * @param frameAspect 정방향 프레임의 가로/세로 비 (0 이하이면 아직 프레임 없음)
 */
@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    frameAspect: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = with(density) { 15.dp.toPx() }
            isAntiAlias = true
            isFakeBoldText = true
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (frameAspect <= 0f || detections.isEmpty()) return@Canvas

        // FILL_CENTER: 프레임이 화면을 다 덮도록 확대 후 중앙 정렬 (넘친 만큼 크롭)
        val viewAspect = size.width / size.height
        val shownWidth: Float
        val shownHeight: Float
        if (frameAspect > viewAspect) {
            shownHeight = size.height
            shownWidth = size.height * frameAspect
        } else {
            shownWidth = size.width
            shownHeight = size.width / frameAspect
        }
        val offsetX = (size.width - shownWidth) / 2f
        val offsetY = (size.height - shownHeight) / 2f

        val stroke = Stroke(width = 3.dp.toPx())
        for (d in detections) {
            val left = offsetX + d.left * shownWidth
            val top = offsetY + d.top * shownHeight
            val right = offsetX + d.right * shownWidth
            val bottom = offsetY + d.bottom * shownHeight

            drawRect(
                color = BoxColor,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = stroke,
            )
            // 라벨은 박스 안쪽 상단에 배경칩과 함께 — 박스가 화면에 꽉 차도 보인다
            val label = "%s %.2f".format(d.label, d.score)
            val pad = 4.dp.toPx()
            val chipHeight = textPaint.textSize + pad * 2
            val chipWidth = textPaint.measureText(label) + pad * 2
            drawRect(
                color = BoxColor,
                topLeft = Offset(left, top),
                size = Size(chipWidth, chipHeight),
            )
            drawContext.canvas.nativeCanvas.drawText(
                label, left + pad, top + chipHeight - pad - textPaint.descent(), textPaint)
        }
    }
}

private val BoxColor = Color(0xFF00E676)
