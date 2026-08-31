package com.example.snap_sight.cv

/**
 * TargetSpec 기반 후보 선택 — `ai/on_device_cv/target_selection.py` 의 Kotlin 포팅.
 *
 * 기본 구현은 [Objects365TargetSelector] 다. 라벨 자산이 없어 taxonomy 를 만들 수 없을 때만
 * [PassThroughTargetSelector] 로 물러난다 (`SnapSightFrameProcessor.create()` 참고).
 * 선택은 tracking **뒤에** 적용하므로, 세션 중 의도가 바뀌어도 이미 추적 중인 객체의
 * `track_id` 는 새로 발급되지 않는다 — 이 순서를 깨면 안 된다.
 */
enum class TargetSelectionState(val wire: String) {
    /** 의도 해석이 비활성화됨 (pass-through fallback). 전체 객체를 그대로 넘긴다. */
    DISABLED("disabled"),
    SELECTED("selected"),
    SEARCHING("searching"),
    AMBIGUOUS("ambiguous"),
    SCENE_ONLY("scene_only"),
    UNRESOLVED("unresolved"),
}

/** 요청 개수 대비 검출 개수 판정. Python `TargetCountStatus` 와 wire 값이 같다. */
enum class TargetCountStatus(val wire: String) {
    NOT_REQUESTED("not_requested"),
    UNDER("under"),
    EXACT("exact"),
    OVER("over"),
    NOT_APPLICABLE("not_applicable"),
}

/**
 * 선택 결과. sessionId/subjectType/framing 등 스펙 유래 필드는 여기 중복하지 않는다 —
 * 소비자는 [CvFrameOutput.targetSpec] 에서 같은 세션의 스펙을 함께 받는다.
 */
data class TargetSelection(
    val state: TargetSelectionState,
    val candidates: List<TrackedObject>,
    val requestedCount: Int? = null,
    val countStatus: TargetCountStatus = TargetCountStatus.NOT_APPLICABLE,
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
 * 폴백 구현: 의도를 해석하지 않고 검출된 전체 객체를 그대로 통과시킨다.
 * spec 이 null 이든 아니든 결과가 같다 — 의도적으로 "아무것도 하지 않음" 을 표현한다.
 */
class PassThroughTargetSelector : TargetSelector {
    override fun select(frameResult: FrameResult, spec: TargetSpec?): TargetSelection =
        TargetSelection(
            state = TargetSelectionState.DISABLED,
            candidates = frameResult.objects,
            requestedCount = spec?.subjectCount,
        )
}

/**
 * `ai/on_device_cv/target_selection.py` 의 `TargetSelector` 포팅.
 * detector/tracker 상태를 건드리지 않고 의도와 일치하는 후보만 고른다.
 *
 * Python 과 같은 규칙:
 *  - `status != ok` → [TargetSelectionState.UNRESOLVED], 후보 없음 (임의 선택 금지)
 *  - `landscape` → [TargetSelectionState.SCENE_ONLY], 객체 target 을 만들지 않음
 *  - `person` → person 후보만
 *  - `object` → taxonomy 소속 non-person 후보, `objectLabel` 이 있으면 해당 class 만
 *  - 요청 개수보다 적으면 SEARCHING, 같으면 SELECTED, 많으면 AMBIGUOUS —
 *    TargetSpec 에는 개체 식별자가 없으므로 임의의 top-N 을 고르는 건 안전하지 않다
 *
 * Python 에는 없는 추가 계약: **spec 이 null 인 세션은 정상 경로다** (마이크 권한 없음,
 * 발화 생략). 이때는 pass-through 와 같이 DISABLED + 전체 객체를 반환한다.
 *
 * @param taxonomy detector 가 쓰는 라벨 자산과 같은 소스로 만들어야 한다.
 * @param personClassId 대체 모델용 override. null 이면 taxonomy 의 `person` class 를 쓴다.
 * @param personLabels class ID 없는 관측(legacy extension)을 person 으로 볼 label 집합.
 */
class Objects365TargetSelector(
    private val taxonomy: ObjectTaxonomy,
    personClassId: Int? = null,
    personLabels: Set<String> = setOf("person"),
) : TargetSelector {

    private val personClassIdOverride: Int?
    private val normalizedPersonLabels: Set<String>

    init {
        require(personClassId == null || personClassId >= 0) {
            "personClassId must be null or a non-negative integer"
        }
        personClassIdOverride = personClassId
        normalizedPersonLabels = personLabels.map { it.trim().lowercase() }.toSet()
        require(normalizedPersonLabels.isNotEmpty() && "" !in normalizedPersonLabels) {
            "personLabels must contain non-empty labels"
        }
    }

    override fun select(frameResult: FrameResult, spec: TargetSpec?): TargetSelection {
        if (spec == null) {
            return TargetSelection(
                state = TargetSelectionState.DISABLED,
                candidates = frameResult.objects,
            )
        }

        if (spec.status != TargetSpec.Status.OK) {
            return TargetSelection(
                state = TargetSelectionState.UNRESOLVED,
                candidates = emptyList(),
                requestedCount = spec.subjectCount,
            )
        }

        if (spec.subjectType.sceneOnly) {
            return TargetSelection(
                state = TargetSelectionState.SCENE_ONLY,
                candidates = emptyList(),
                requestedCount = spec.subjectCount,
            )
        }

        var candidates = if (spec.subjectType == TargetSpec.SubjectType.PERSON) {
            frameResult.objects.filter(::isPerson)
        } else {
            frameResult.objects
                .filter { taxonomy.isSupportedObject(it.classId, it.label) }
                .let { supported ->
                    val objectLabel = spec.objectLabel ?: return@let supported
                    supported.filter { taxonomy.matches(it.classId, it.label, objectLabel) }
                }
        }
        candidates = candidates.sortedBy { it.trackId }

        val requestedCount = spec.subjectCount
        val (countStatus, state) = when {
            requestedCount == null ->
                TargetCountStatus.NOT_REQUESTED to
                        if (candidates.isNotEmpty()) TargetSelectionState.SELECTED
                        else TargetSelectionState.SEARCHING
            candidates.size < requestedCount ->
                TargetCountStatus.UNDER to TargetSelectionState.SEARCHING
            candidates.size == requestedCount ->
                TargetCountStatus.EXACT to TargetSelectionState.SELECTED
            else ->
                TargetCountStatus.OVER to TargetSelectionState.AMBIGUOUS
        }

        return TargetSelection(
            state = state,
            candidates = candidates,
            requestedCount = requestedCount,
            countStatus = countStatus,
        )
    }

    private fun isPerson(item: TrackedObject): Boolean {
        if (personClassIdOverride != null && item.classId != null) {
            return item.classId == personClassIdOverride
        }
        if (item.classId == null && item.label.trim().lowercase() in normalizedPersonLabels) {
            return true
        }
        return taxonomy.matches(item.classId, item.label, canonicalLabel = "person")
    }
}
