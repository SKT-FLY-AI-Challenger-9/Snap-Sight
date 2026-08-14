package com.example.snap_sight.cv

/**
 * TargetSpec 기반 후보 선택의 **확장 자리**.
 *
 * 지금은 [PassThroughTargetSelector] 하나뿐이고 의도를 해석하지 않는다.
 * ① STT/NLU 가 붙으면 `ai/on_device_cv/target_selection.py` 를 포팅한 구현으로 교체한다.
 * 선택은 tracking **뒤에** 적용하므로, 세션 중 의도가 바뀌어도 이미 추적 중인 객체의
 * `track_id` 는 새로 발급되지 않는다 — 이 순서를 깨면 안 된다.
 */
enum class TargetSelectionState(val wire: String) {
    /** 의도 해석이 아직 활성화되지 않음 (현재 기본값). 전체 객체를 그대로 넘긴다. */
    DISABLED("disabled"),
    SELECTED("selected"),
    SEARCHING("searching"),
    AMBIGUOUS("ambiguous"),
    SCENE_ONLY("scene_only"),
    UNRESOLVED("unresolved"),
}

data class TargetSelection(
    val state: TargetSelectionState,
    val candidates: List<TrackedObject>,
    val requestedCount: Int? = null,
) {
    val detectedCount: Int get() = candidates.size

    /** 후보를 기존 공개 객체 스키마로 다시 담는다. */
    fun toFrameResult(): FrameResult = FrameResult(candidates)
}

interface TargetSelector {
    /** @param spec null 이면 의도 없는 세션이다. 구현체는 반드시 이 경우를 처리해야 한다. */
    fun select(frameResult: FrameResult, spec: TargetSpec?): TargetSelection
}

/**
 * 현재 단계의 기본 구현: 의도를 해석하지 않고 검출된 전체 객체를 그대로 통과시킨다.
 * spec 이 null 이든 아니든 결과가 같다 — 의도적으로 "아직 아무것도 하지 않음" 을 표현한다.
 */
class PassThroughTargetSelector : TargetSelector {
    override fun select(frameResult: FrameResult, spec: TargetSpec?): TargetSelection =
        TargetSelection(
            state = TargetSelectionState.DISABLED,
            candidates = frameResult.objects,
            requestedCount = spec?.subjectCount,
        )
}
