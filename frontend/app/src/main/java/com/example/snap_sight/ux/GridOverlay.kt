// 이 파일: 촬영 미리보기 위에 3×3 구도선을 그리는 덧그림.
// 저시력 사용자용 시각 채널 — 전맹 사용자에겐 소리·진동이 같은 정보를 전달한다.
package com.example.snap_sight.ux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 격자 표시 단계 — "최종 기획 정리" 화면 설정의 격자(끔 / 기본 / 강조).
 *
 * 저장값은 [name]으로 직렬화하므로 이름을 바꾸면 기존 사용자의 설정이 초기화된다.
 */
enum class GridMode(val label: String, val lineWidth: Dp) {
    OFF("끔", 0.dp),
    BASIC("기본", 4.dp),
    STRONG("강조", 6.dp),
    ;

    companion object {
        val DEFAULT = BASIC

        fun fromName(name: String?): GridMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 3×3 구도선을 미리보기 위에 그린다.
 *
 * 선은 화면 전체를 가로·세로로 3등분하는 위치에 놓는다. 미리보기가 FIT_CENTER 라
 * 레터박스가 생길 수 있지만, 격자는 프레임이 아니라 **화면 기준 구도 보조선**이므로
 * [DetectionOverlay]와 달리 프레임 좌표 변환을 하지 않는다.
 *
 * 색만으로 정보를 전달하지 않는다는 원칙에 따라, 격자는 단계 표시가 아니라 고정 보조선이다.
 * 단계(감지/완료)는 [DetectionOverlay]의 상자 색과 소리·진동이 담당한다.
 *
 * @param mode [GridMode.OFF] 이면 아무것도 그리지 않는다.
 * @param color 선 색. 기본은 흰색이며, 밝은 장면에서 묻히지 않도록 [outlineColor] 외곽선을 함께 그린다.
 */
@Composable
fun GridOverlay(
    mode: GridMode,
    modifier: Modifier = Modifier,
    color: Color = DEFAULT_GRID_COLOR,
    outlineColor: Color = DEFAULT_OUTLINE_COLOR,
) {
    if (mode == GridMode.OFF) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val lineWidth = mode.lineWidth.toPx()
        // 흰 선이 밝은 하늘·벽에서 사라지지 않도록 어두운 외곽선을 먼저 깔고 그 위에 본선을 얹는다.
        val outlineWidth = lineWidth + (OUTLINE_DP * 2).dp.toPx()

        val columns = listOf(size.width / 3f, size.width * 2f / 3f)
        val rows = listOf(size.height / 3f, size.height * 2f / 3f)

        fun drawVertical(x: Float, strokeWidth: Float, strokeColor: Color) {
            drawLine(
                color = strokeColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = strokeWidth,
            )
        }

        fun drawHorizontal(y: Float, strokeWidth: Float, strokeColor: Color) {
            drawLine(
                color = strokeColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = strokeWidth,
            )
        }

        columns.forEach { drawVertical(it, outlineWidth, outlineColor) }
        rows.forEach { drawHorizontal(it, outlineWidth, outlineColor) }
        columns.forEach { drawVertical(it, lineWidth, color) }
        rows.forEach { drawHorizontal(it, lineWidth, color) }
    }
}

/**
 * 흰색 구도선.
 *
 * "최종 기획 정리"는 격자를 노랑(#FFD400)으로 확정해 뒀으나, 촬영 화면 요구가 흰색이라
 * 흰색을 기본값으로 둔다. 노랑으로 되돌리려면 이 상수만 바꾸면 된다 — 저시력 사용자
 * 대비 확보가 목적이었으므로 복지관 테스트에서 재확인할 항목이다.
 */
private val DEFAULT_GRID_COLOR = Color.White

/** 밝은 배경에서 흰 선이 묻히지 않도록 두르는 반투명 검정 외곽선. */
private val DEFAULT_OUTLINE_COLOR = Color(0x99000000)

/** 외곽선이 본선 바깥으로 나오는 두께(한쪽 기준, dp). */
private const val OUTLINE_DP = 1
