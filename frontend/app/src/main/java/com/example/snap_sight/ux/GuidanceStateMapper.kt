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

    const val MAX_ABS_X_DEVIATION = 0.15f
    const val MAX_ABS_SIZE_DEVIATION = 0.10f

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
        return GuidanceState(detected = true, horizontal = horizontal, distance = distance)
    }
}
