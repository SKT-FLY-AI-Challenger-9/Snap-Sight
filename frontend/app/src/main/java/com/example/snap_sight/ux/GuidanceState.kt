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
     * 수직 정렬. dy가 없으면 null이며, 이 경우 안전하게 READY가 아니다.
     */
    val vertical: VerticalAlignment? = null,
    /** y·visibility·freshness까지 반영한 canonical single-frame 후보 판정. */
    val canonicalReadyCandidate: Boolean = false,
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
            require(!canonicalReadyCandidate) {
                "detected=false인 경우 canonicalReadyCandidate는 false여야 합니다."
            }
        }
    }

    /** 수평·수직·거리뿐 아니라 visibility/freshness까지 통과한 단일-frame 후보 상태. */
    val isReady: Boolean
        get() = canonicalReadyCandidate
}

enum class HorizontalAlignment { LEFT, RIGHT, CENTERED }
enum class DistanceAlignment { CLOSER, FARTHER, CENTERED }
enum class VerticalAlignment { UP, DOWN, CENTERED }
