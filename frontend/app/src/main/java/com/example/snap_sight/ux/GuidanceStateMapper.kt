package com.example.snap_sight.ux

import com.example.snap_sight.cv.DeviationResult

/**
 * [DeviationResult] → [GuidanceState] 판정.
 *
 * 임계값은 초기 추정치다 — 선행연구·실측 데이터 근거가 없는 순수 추정치이며,
 * 이슈 #42의 실기기 편차 분포 데이터를 확보한 뒤 조정 예정이다.
 *
 * **정본은 이 파일이 아니라 `docs/ux/guidance-state-schema.md`다.** 값을 바꿀 때는
 * 그 문서도 같이 갱신한다 (반대로 문서를 바꿀 때도 이 상수를 같이 바꾼다).
 */
object GuidanceStateMapper {

    const val MAX_ABS_X_DEVIATION = 0.1f
    const val MAX_ABS_SIZE_DEVIATION = 0.05f

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
