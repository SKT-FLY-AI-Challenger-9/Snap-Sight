package com.example.snap_sight.cv

/**
 * 온디바이스 CV(②)가 산출하는 단일 탐지 결과.
 *
 * 좌표계 계약 (② #2 와 합의 대상):
 *  - 회전 보정이 끝난 "정방향" 프레임 기준.
 *  - left/top/right/bottom 은 0.0 ~ 1.0 정규화 좌표 (프레임 크기와 무관).
 *  - (0,0) = 좌상단, (1,1) = 우하단.
 *
 * label 은 `ai/target_spec_schema.md` 의 objectLabel 허용값과 같은
 * snake_case COCO 클래스명을 사용한다 (예: "cell_phone", "wine_glass").
 */
data class Detection(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** 박스 중심 X (0..1). ③ 편차 판정의 좌우 기준값. */
    val centerX: Float get() = (left + right) / 2f

    /** 박스 중심 Y (0..1). */
    val centerY: Float get() = (top + bottom) / 2f

    /** 프레임 대비 박스 면적 비율 (0..1). ③ 거리(다가가기/물러나기) 판정 기준값. */
    val areaRatio: Float get() = ((right - left) * (bottom - top)).coerceAtLeast(0f)
}
