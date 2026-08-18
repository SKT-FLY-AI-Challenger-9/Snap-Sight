package com.example.snap_sight.ux

import com.example.snap_sight.cv.DeviationResult

/**
 * [DeviationResult] → [GuidanceState] 판정.
 *
 * 임계값은 이슈 #42의 실기기(갤럭시 S24) 편차 분포로 1차 캘리브레이션된 값이다:
 * 조준 중 |x| 중앙값 0.123 / |size| 중앙값 0.086 이 관측돼, 기존 추정치(0.1/0.05)로는
 * READY 도달률이 3%에 그쳤다. 중앙값 + 손떨림 여유로 상향 (도달률 재측정은 후속).
 *
 * **정본은 이 파일이 아니라 `docs/ux/guidance-state-schema.md`다.** 값을 바꿀 때는
 * 그 문서도 같이 갱신한다 (반대로 문서를 바꿀 때도 이 상수를 같이 바꾼다).
 */
object GuidanceStateMapper {

    /** 2026-08-19 실사용 피드백으로 0.15 → 0.20 완화 (프레임 폭의 ±20% 안이면 CENTERED). */
    const val MAX_ABS_X_DEVIATION = 0.20f
    const val MAX_ABS_SIZE_DEVIATION = 0.10f
    /** 수직 허용 오차 [추정] — 세로 구도는 여유를 더 둔다(전신은 중심이 아래로 치우치기 쉬움). */
    const val MAX_ABS_Y_DEVIATION = 0.25f
    /**
     * READY 유지 히스테리시스 — 한 번 READY 에 들어오면 임계값의 이 배수를 넘어야 벗어난 것으로 본다.
     * 손떨림으로 READY ↔ 방향 안내가 튀는 것을 막는다. [GuidancePolicy] 가 쓴다.
     */
    const val READY_EXIT_FACTOR = 1.5f

    fun from(result: DeviationResult): GuidanceState {
        if (!result.subjectDetected) {
            return GuidanceState(detected = false, horizontal = null, distance = null)
        }
        val x = requireNotNull(result.xDeviation) { "subjectDetected=true인데 xDeviation이 null" }
        val size = requireNotNull(result.sizeDeviation) { "subjectDetected=true인데 sizeDeviation이 null" }

        val horizontal = when {
            x < -MAX_ABS_X_DEVIATION -> HorizontalAlignment.LEFT
            x > MAX_ABS_X_DEVIATION -> HorizontalAlignment.RIGHT
            else -> HorizontalAlignment.CENTERED
        }
        val distance = when {
            size < -MAX_ABS_SIZE_DEVIATION -> DistanceAlignment.CLOSER
            size > MAX_ABS_SIZE_DEVIATION -> DistanceAlignment.FARTHER
            else -> DistanceAlignment.CENTERED
        }
        // y: 음수 = 피사체가 프레임 중심보다 위 → 카메라를 위로 올려야 한다
        val vertical = result.yDeviation?.let { y ->
            when {
                y < -MAX_ABS_Y_DEVIATION -> VerticalAlignment.UP
                y > MAX_ABS_Y_DEVIATION -> VerticalAlignment.DOWN
                else -> VerticalAlignment.CENTERED
            }
        }
        return GuidanceState(detected = true, horizontal = horizontal, distance = distance, vertical = vertical)
    }
}
