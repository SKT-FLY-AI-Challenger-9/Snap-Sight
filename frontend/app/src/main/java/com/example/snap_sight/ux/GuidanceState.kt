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
    /**
     * 수직 정렬 — additive. CV 계약의 dy 반영이 미확정이라 READY 판정([isReady])에는 넣지 않고
     * 방향 음성 안내("위/아래")에만 쓴다. dy 가 없으면 null.
     */
    val vertical: VerticalAlignment? = null,
) {
    init {
        if (detected) {
            require(horizontal != null && distance != null) {
                "detected=true인 경우 horizontal/distance는 비어 있으면 안 됩니다."
            }
        } else {
            require(horizontal == null && distance == null && vertical == null) {
                "detected=false인 경우 horizontal/distance/vertical은 모두 null이어야 합니다."
            }
        }
    }

    /** 탐지됨 + 수평·거리 모두 CENTERED — 촬영 가능 상태. (수직은 판정에 포함하지 않는다) */
    val isReady: Boolean
        get() = detected && horizontal == HorizontalAlignment.CENTERED && distance == DistanceAlignment.CENTERED
}

enum class HorizontalAlignment { LEFT, RIGHT, CENTERED }
enum class DistanceAlignment { CLOSER, FARTHER, CENTERED }
enum class VerticalAlignment { UP, DOWN, CENTERED }
