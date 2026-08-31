// 이 파일: 촬영 화면 가로모드 UI (2026-08-31) — Activity 는 세로 고정을 유지한 채, 갤럭시 카메라처럼
// 기기를 눕히면 레이아웃 골격은 그대로 두고 "요소만" 제자리에서 회전시킨다. 세로모드의 위·아래 띠가
// 가로모드 기준으로는 화면 왼쪽·오른쪽 끝이 되고, 그 안의 요소들이 돌아서 똑바로 보이는 방식이다.
// 기기 방향값은 CameraController 의 물리 방향 센서(촬영 회전과 같은 소스)를 그대로 쓴다.
package com.example.snap_sight.ux

import android.view.Surface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

/**
 * Surface 회전값 → UI 요소가 사용자 눈에 똑바로 보이기 위해 돌아야 하는 각도(시계방향 +).
 * 거꾸로 든 세로(ROTATION_180)는 갤럭시 카메라와 같이 회전하지 않는다.
 * 촬영 저장 회전은 CameraController 가 ImageCapture.targetRotation 으로 별도 처리하므로
 * 여기 각도는 순수하게 화면 표시용이다.
 */
fun uiRotationDegrees(surfaceRotation: Int): Float = when (surfaceRotation) {
    Surface.ROTATION_90 -> 90f   // 기기 상단이 왼쪽 — 일반적인 가로 파지
    Surface.ROTATION_270 -> -90f // 기기 상단이 오른쪽
    else -> 0f
}

/** UI 관점의 가로모드 여부 — ROTATION_180 은 세로 취급. */
fun isLandscapeUi(surfaceRotation: Int): Boolean = uiRotationDegrees(surfaceRotation) != 0f

/**
 * 콤팩트 요소(칩·짧은 라벨) 제자리 회전 — 레이아웃 크기·위치는 그대로 두고 그리기만
 * 부드럽게 돌린다. 정사각형에 가까운 요소에 쓴다. 긴 요소는 [QuarterRotated] 로.
 */
@Composable
fun Modifier.rotateWithDevice(surfaceRotation: Int): Modifier {
    val angle by animateFloatAsState(
        targetValue = uiRotationDegrees(surfaceRotation),
        animationSpec = tween(durationMillis = 250),
        label = "deviceUiRotation",
    )
    return graphicsLayer { rotationZ = angle }
}

/**
 * 자식을 90° 눕혀 배치하는 레이아웃 — [Modifier.graphicsLayer] 단독 회전과 달리 측정 제약과
 * 최종 크기도 함께 눕힌다(가로↔세로 스왑). 가로모드 한 줄 안내처럼 세로 화면 높이를 따라
 * "세워야" 하는 긴 요소에 쓴다.
 *
 * @param clockwise true = 시계방향 90° (ROTATION_90, 기기 상단이 왼쪽일 때 똑바로 보임),
 *                  false = 반시계 90° (ROTATION_270)
 */
@Composable
fun QuarterRotated(
    clockwise: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = { Box { content() } }, modifier = modifier) { measurables, constraints ->
        val swapped = Constraints(
            minWidth = 0,
            maxWidth = constraints.maxHeight,
            minHeight = 0,
            maxHeight = constraints.maxWidth,
        )
        val placeable = measurables.first().measure(swapped)
        layout(placeable.height, placeable.width) {
            placeable.placeWithLayer(0, 0) {
                transformOrigin = TransformOrigin(0f, 0f)
                rotationZ = if (clockwise) 90f else -90f
                // 원점 기준 회전이라 자식이 레이아웃 밖으로 나간다 — 제자리로 평행이동
                if (clockwise) {
                    translationX = placeable.height.toFloat()
                } else {
                    translationY = placeable.width.toFloat()
                }
            }
        }
    }
}
