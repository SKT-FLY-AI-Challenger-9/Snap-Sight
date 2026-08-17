package com.example.snap_sight.ux

/**
 * ⑥ 판정 결과 — [com.example.snap_sight.cv.DeviationResult]를 사용자 안내용 상태로 변환한 것.
 * 판정 로직(임계값 적용)은 [GuidanceStateMapper] 참고 — 이 파일은 데이터 형태만 정의한다.
 *
 * 근거: docs/ux/guidance-state-schema.md
 */
data class GuidanceState(
    val detected: Boolean,
    val horizontal: HorizontalAlignment?,
    val distance: DistanceAlignment?,
) {
    init {
        if (detected) {
            require(horizontal != null && distance != null) {
                "detected=true인 경우 horizontal/distance는 비어 있으면 안 됩니다."
            }
        } else {
            require(horizontal == null && distance == null) {
                "detected=false인 경우 horizontal/distance는 모두 null이어야 합니다."
            }
        }
    }

    /** 탐지됨 + 수평·거리 모두 CENTERED — 촬영 가능 상태. */
    val isReady: Boolean
        get() = detected && horizontal == HorizontalAlignment.CENTERED && distance == DistanceAlignment.CENTERED
}

enum class HorizontalAlignment { LEFT, RIGHT, CENTERED }
enum class DistanceAlignment { CLOSER, FARTHER, CENTERED }
