// 이 파일: 발화로 지정한 objectLabel과 일치하는 후보만 골라내는 임시 타겟 선택기.
// ②의 target_selection 정식 포팅이 도착하면 교체한다.
package com.example.snap_sight.cv

class LabelMatchTargetSelector : TargetSelector {

    override fun select(frameResult: FrameResult, spec: TargetSpec?): TargetSelection {
        val label = spec?.objectLabel
        val matched = if (label != null) frameResult.objects.filter { it.label == label } else emptyList()
        return if (matched.isNotEmpty()) {
            TargetSelection(
                state = TargetSelectionState.SELECTED,
                candidates = matched,
                requestedCount = spec?.subjectCount,
            )
        } else {
            // 의도가 없거나 일치 라벨이 없으면 기존 동작(전체 통과) 유지
            TargetSelection(state = TargetSelectionState.DISABLED, candidates = frameResult.objects)
        }
    }
}
