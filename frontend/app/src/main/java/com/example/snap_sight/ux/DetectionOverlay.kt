// 이 파일: AI가 찾은 물체 위에 노란 라운드 박스를 그려주는 덧그림 (Make 시안 v31의 추적 상자).
// 시각 사용자·데모 참관자를 위한 보조 표시 — 실제 사용자(시각장애인)용 안내는 소리·진동으로 나간다.
package com.example.snap_sight.ux

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.snap_sight.cv.TrackedObject

/**
 * ② CV 스트림(추적 객체)을 미리보기 위에 그리는 오버레이 — 시안의 노란 추적 상자.
 *
 * 좌표 매핑: bbox 는 정방향(사용자 기준) 분석 프레임의 0..1 정규화 좌표다. 기기를 눕히면
 * 분석 프레임도 같이 돌지만(CameraController 2026-08-31) 화면 미리보기는 세로 프레임 그대로라,
 * [UprightFrameMapping] 으로 세로 프레임 좌표로 되돌린 뒤 FIT_CENTER(CaptureScreen 참고 —
 * 균등 축소·중앙 정렬, 남는 부분은 검은 띠) 변환을 적용해야 박스가 미리보기와 일치한다.
 *
 * @param frameAspect    화면에 보이는 세로 프레임의 가로/세로 비 (기본 480x640 세로 = 0.75)
 * @param mirrored       전면(셀카) 카메라 — 미리보기는 좌우 반전돼 보이지만 분석 프레임은
 *                       반전이 없으므로, 박스 x 좌표를 뒤집어야 미리보기와 일치한다
 * @param deviceRotation 기기 물리 방향 (Surface.ROTATION_*) — CameraController.deviceRotationFlow
 * @param identities     track_id → 등록 인물·사물 이름. 포함된 상자는 **초록색 + 이름 라벨**로 그려
 *                       일반 탐지(노랑)와 구분한다 (2026-08-22 피드백)
 */
@Composable
fun DetectionOverlay(
    objects: List<TrackedObject>,
    frameAspect: Float = 3f / 4f,
    mirrored: Boolean = false,
    deviceRotation: Int = 0,
    identities: Map<Int, String> = emptyMap(),
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
        // 예측(coasting) 상자는 점선·반투명 — "지금 보고 있는 게 아니라 이어가는 중"을 구분한다
        val predictedStroke = Stroke(
            width = 3.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())),
        )
        val corner = CornerRadius(14.dp.toPx(), 14.dp.toPx())
        val labelPaint = Paint().apply {
            color = SnapPalette.Success.toArgb()
            textSize = 14.sp.toPx()
            isAntiAlias = true
            isFakeBoldText = true
        }
        for (o in objects) {
            // 정방향 프레임 → 세로 프레임 (회전 시 min/max 역할이 바뀌므로 다시 정렬)
            val (ax, ay) = UprightFrameMapping.toDisplayPoint(o.bbox.xMin, o.bbox.yMin, deviceRotation, mirrored)
            val (bx, by) = UprightFrameMapping.toDisplayPoint(o.bbox.xMax, o.bbox.yMax, deviceRotation, mirrored)
            val left = offsetX + minOf(ax, bx) * shownWidth
            val top = offsetY + minOf(ay, by) * shownHeight
            val right = offsetX + maxOf(ax, bx) * shownWidth
            val bottom = offsetY + maxOf(ay, by) * shownHeight

            val name = identities[o.trackId]
            val base = if (name != null) SnapPalette.Success else SnapPalette.Warning
            drawRoundRect(
                color = if (o.predicted) base.copy(alpha = 0.55f) else base,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = corner,
                style = if (o.predicted) predictedStroke else stroke,
            )
            if (name != null) {
                drawContext.canvas.nativeCanvas.drawText(
                    name, left + 10.dp.toPx(), top + 20.dp.toPx(), labelPaint,
                )
            }
        }
    }
}

/**
 * 서류 모드 외곽 오버레이 (2026-08-31) — [DocumentObservation] 경계를 파란색으로 그린다.
 * 엣지 탐색으로 **확정된 변은 실선**, 글자 여백 폴백 변은 **점선** — 실기기에서 v1 엣지가
 * 어디까지 잡히는지 눈으로 확인하는 튜닝 도구이자 데모 표시. 좌표 매핑은 [DetectionOverlay]
 * 와 동일(FIT_CENTER).
 */
@Composable
fun DocumentOutlineOverlay(
    outline: DocumentObservation?,
    frameAspect: Float = 3f / 4f,
    mirrored: Boolean = false,
    deviceRotation: Int = 0,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (outline == null || frameAspect <= 0f) return@Canvas
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

        // 회전·반전을 점 단위로 처리한다 — 변 확정 플래그는 점을 따라가므로 별도 스왑이 필요 없다
        fun map(x: Float, y: Float): Offset {
            val (px, py) = UprightFrameMapping.toDisplayPoint(x, y, deviceRotation, mirrored)
            return Offset(offsetX + px * shownWidth, offsetY + py * shownHeight)
        }

        // 모서리 4점이 있으면 실제 모양(사다리꼴 포함)을 그대로 그린다 (외곽 v2, 2026-08-31)
        val quad = outline.corners
        if (quad != null) {
            val points = listOf(
                map(quad.tl.x, quad.tl.y), map(quad.tr.x, quad.tr.y),
                map(quad.br.x, quad.br.y), map(quad.bl.x, quad.bl.y),
            )
            for (i in points.indices) {
                drawLine(
                    color = SnapPalette.AccentLight,
                    start = points[i],
                    end = points[(i + 1) % points.size],
                    strokeWidth = 3.dp.toPx(),
                )
            }
            return@Canvas
        }

        val tl = map(outline.left, outline.top)
        val tr = map(outline.right, outline.top)
        val br = map(outline.right, outline.bottom)
        val bl = map(outline.left, outline.bottom)

        val strokeWidth = 3.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
        fun side(from: Offset, to: Offset, confirmed: Boolean) {
            drawLine(
                color = if (confirmed) SnapPalette.AccentLight else SnapPalette.AccentLight.copy(alpha = 0.55f),
                start = from,
                end = to,
                strokeWidth = strokeWidth,
                pathEffect = if (confirmed) null else dash,
            )
        }
        side(tl, tr, outline.edgeTop)
        side(br, bl, outline.edgeBottom)
        side(bl, tl, outline.edgeLeft)
        side(tr, br, outline.edgeRight)
    }
}
