package com.example.snap_sight.face

import com.example.snap_sight.cv.BoundingBox
import kotlin.math.sqrt

/**
 * 등록 프레임에서 "사용자가 카메라 중앙에 보여 주는 한 대상"만 고르는 보수적 정책.
 *
 * 단순히 가장 큰 박스를 택하면 옆 사람이나 배경의 큰 사물을 잘못 등록할 수 있다. 따라서 가장 큰
 * 후보가 화면 중앙에 충분히 가깝고, 두 번째 후보보다 면적이 명확히 클 때만 그 인덱스를 반환한다.
 * 모호하면 null을 반환해 그 프레임만 건너뛴다.
 */
internal object EnrollmentCandidateSelector {
    const val MAXIMUM_CENTER_DISTANCE = 0.30f
    const val MINIMUM_AREA_DOMINANCE_RATIO = 1.50f

    fun selectIndex(
        boxes: List<BoundingBox>,
        maximumCenterDistance: Float = MAXIMUM_CENTER_DISTANCE,
        minimumAreaDominanceRatio: Float = MINIMUM_AREA_DOMINANCE_RATIO,
    ): Int? {
        require(maximumCenterDistance.isFinite() && maximumCenterDistance >= 0f) {
            "maximumCenterDistance must be non-negative and finite"
        }
        require(minimumAreaDominanceRatio.isFinite() && minimumAreaDominanceRatio >= 1f) {
            "minimumAreaDominanceRatio must be finite and at least 1"
        }
        if (boxes.isEmpty()) return null

        val rankedIndices = boxes.indices.sortedWith(
            compareByDescending<Int> { boxes[it].area }
                .thenBy { centerDistance(boxes[it]) }
                .thenBy { it },
        )
        val primaryIndex = rankedIndices.first()
        val primary = boxes[primaryIndex]
        if (centerDistance(primary) > maximumCenterDistance) return null

        val runnerUp = rankedIndices.getOrNull(1)?.let(boxes::get)
        if (runnerUp != null && primary.area < runnerUp.area * minimumAreaDominanceRatio) return null
        return primaryIndex
    }

    private fun centerDistance(box: BoundingBox): Float {
        val dx = box.centerX - 0.5f
        val dy = box.centerY - 0.5f
        return sqrt(dx * dx + dy * dy)
    }
}
