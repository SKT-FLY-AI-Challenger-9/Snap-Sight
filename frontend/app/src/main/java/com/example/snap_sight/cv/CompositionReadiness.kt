package com.example.snap_sight.cv

import kotlin.math.abs

enum class FrameEdge { LEFT, TOP, RIGHT, BOTTOM }

/** normalized bbox와 각 프레임 경계 사이의 여백. 0이면 해당 방향이 잘렸을 가능성이 높다. */
data class FrameVisibility(
    val leftMargin: Float,
    val topMargin: Float,
    val rightMargin: Float,
    val bottomMargin: Float,
) {
    init {
        for (value in listOf(leftMargin, topMargin, rightMargin, bottomMargin)) {
            require(value.isFinite() && value in 0f..1f) { "frame margins must be in [0, 1]" }
        }
    }

    fun margin(edge: FrameEdge): Float = when (edge) {
        FrameEdge.LEFT -> leftMargin
        FrameEdge.TOP -> topMargin
        FrameEdge.RIGHT -> rightMargin
        FrameEdge.BOTTOM -> bottomMargin
    }

    companion object {
        fun from(bbox: BoundingBox) = FrameVisibility(
            leftMargin = bbox.xMin,
            topMargin = bbox.yMin,
            rightMargin = 1f - bbox.xMax,
            bottomMargin = 1f - bbox.yMax,
        )
    }
}

/** 한 프레이밍의 실제 좌표 anchor와 촬영 준비 조건. */
data class FramingGoal(
    val anchorX: Float,
    val anchorY: Float,
    val targetAreaRatio: Float,
    val maxAbsXDeviation: Float,
    val maxAbsYDeviation: Float,
    val maxAbsAreaDeviation: Float,
    val requiredVisibleEdges: Set<FrameEdge> = emptySet(),
    val minimumVisibleMargin: Float = 0f,
    val maximumObservationAgeMs: Long = 450L,
    val stabilityMs: Long = 300L,
    val readyExitFactor: Float = 1.5f,
) {
    init {
        require(anchorX.isFinite() && anchorX in 0f..1f) { "anchorX must be in [0, 1]" }
        require(anchorY.isFinite() && anchorY in 0f..1f) { "anchorY must be in [0, 1]" }
        require(targetAreaRatio.isFinite() && targetAreaRatio in 0f..1f) {
            "targetAreaRatio must be in [0, 1]"
        }
        for ((name, value) in listOf(
            "maxAbsXDeviation" to maxAbsXDeviation,
            "maxAbsYDeviation" to maxAbsYDeviation,
            "maxAbsAreaDeviation" to maxAbsAreaDeviation,
            "minimumVisibleMargin" to minimumVisibleMargin,
        )) {
            require(value.isFinite() && value >= 0f) { "$name must be non-negative and finite" }
        }
        require(minimumVisibleMargin <= 1f) { "minimumVisibleMargin must be at most 1" }
        require(maximumObservationAgeMs >= 0L) { "maximumObservationAgeMs must be non-negative" }
        require(stabilityMs >= 0L) { "stabilityMs must be non-negative" }
        require(readyExitFactor.isFinite() && readyExitFactor >= 1f) {
            "readyExitFactor must be finite and at least 1"
        }
    }
}

/** 프레이밍별 목표를 한 곳에서 주입·캘리브레이션하기 위한 프로필. */
data class CompositionProfile(
    val closeup: FramingGoal = FramingGoal(
        anchorX = 0.5f,
        anchorY = 0.42f,
        targetAreaRatio = 0.30f,
        maxAbsXDeviation = 0.20f,
        maxAbsYDeviation = 0.18f,
        maxAbsAreaDeviation = 0.10f,
    ),
    val fullBody: FramingGoal = FramingGoal(
        anchorX = 0.5f,
        anchorY = 0.5f,
        targetAreaRatio = 0.12f,
        maxAbsXDeviation = 0.20f,
        maxAbsYDeviation = 0.22f,
        maxAbsAreaDeviation = 0.10f,
        requiredVisibleEdges = setOf(FrameEdge.TOP, FrameEdge.BOTTOM),
        minimumVisibleMargin = 0.005f,
    ),
    val wide: FramingGoal = FramingGoal(
        anchorX = 0.5f,
        anchorY = 0.5f,
        targetAreaRatio = 0.04f,
        maxAbsXDeviation = 0.20f,
        maxAbsYDeviation = 0.25f,
        maxAbsAreaDeviation = 0.10f,
    ),
) {
    fun goalFor(framing: TargetSpec.Framing): FramingGoal = when (framing) {
        TargetSpec.Framing.CLOSEUP -> closeup
        TargetSpec.Framing.FULL_BODY -> fullBody
        TargetSpec.Framing.WIDE -> wide
    }

    companion object {
        val DEFAULT = CompositionProfile()
    }
}

enum class ReadinessBlocker {
    SUBJECT_NOT_DETECTED,
    HORIZONTAL,
    VERTICAL,
    SIZE,
    VISIBILITY,
    PREDICTED,
    HELD,
    STALE,
    UNSTABLE,
}

data class ReadinessVerdict(
    /** 안정화 시간과 READY 이탈 hysteresis까지 반영한 최종 촬영 가능 여부. */
    val ready: Boolean,
    /** 진입 임계값을 현재 한 번 만족하는지(안정화 시간 제외). */
    val candidateReady: Boolean,
    val blockers: Set<ReadinessBlocker>,
    val stableForMs: Long,
    val goal: FramingGoal,
)

/**
 * READY 판정의 단일 정본. x/size뿐 아니라 y, bbox 잘림, 관측 freshness/age를 항상 함께 본다.
 */
object CompositionReadiness {
    fun candidateVerdict(
        result: DeviationResult,
        profile: CompositionProfile = CompositionProfile.DEFAULT,
    ): ReadinessVerdict {
        val goal = result.goal ?: profile.goalFor(result.framing)
        val blockers = blockers(result, goal, thresholdFactor = 1f)
        return ReadinessVerdict(
            ready = blockers.isEmpty() && goal.stabilityMs == 0L,
            candidateReady = blockers.isEmpty(),
            blockers = blockers,
            stableForMs = 0L,
            goal = goal,
        )
    }

    internal fun blockers(
        result: DeviationResult,
        goal: FramingGoal,
        thresholdFactor: Float,
    ): Set<ReadinessBlocker> {
        if (!result.subjectDetected) return setOf(ReadinessBlocker.SUBJECT_NOT_DETECTED)
        val blockers = linkedSetOf<ReadinessBlocker>()
        val x = result.xDeviation
        val y = result.yDeviation
        val size = result.sizeDeviation
        if (x == null || abs(x) > goal.maxAbsXDeviation * thresholdFactor) {
            blockers += ReadinessBlocker.HORIZONTAL
        }
        // y가 없는 legacy 결과도 READY로 승격하지 않는다.
        if (y == null || abs(y) > goal.maxAbsYDeviation * thresholdFactor) {
            blockers += ReadinessBlocker.VERTICAL
        }
        // 크기 초과(FARTHER)는 READY 를 막지 않는다 (2026-08-23) — "뒤로 이동"은 안내하지 않는
        // 정책과 짝: 사용자가 해소할 수 없는 조건으로 촬영을 막고 침묵하는 대신, 너무 클 때는
        // 그대로 찍게 두고 처리(크롭 등)는 후처리로 넘긴다. 너무 작음(음수)만 막는다.
        if (size == null || size < -goal.maxAbsAreaDeviation * thresholdFactor) {
            blockers += ReadinessBlocker.SIZE
        }
        val visibility = result.frameVisibility
        if (visibility != null && goal.requiredVisibleEdges.any {
                visibility.margin(it) < goal.minimumVisibleMargin
            }) {
            blockers += ReadinessBlocker.VISIBILITY
        }
        when (result.observationFreshness) {
            ObservationFreshness.FRESH -> Unit
            ObservationFreshness.PREDICTED -> blockers += ReadinessBlocker.PREDICTED
            ObservationFreshness.HELD -> blockers += ReadinessBlocker.HELD
        }
        if (result.observationAgeMs > goal.maximumObservationAgeMs) blockers += ReadinessBlocker.STALE
        return blockers
    }
}

/** 시간 기반 stability와 READY 이탈 hysteresis를 더하는 세션 단위 evaluator. */
class CanonicalReadinessEvaluator(
    private val profile: CompositionProfile = CompositionProfile.DEFAULT,
) {
    private var candidateSinceMs: Long? = null
    private var lastEvaluatedAtMs: Long? = null
    private var inReady = false

    @Synchronized
    fun reset() {
        candidateSinceMs = null
        lastEvaluatedAtMs = null
        inReady = false
    }

    @Synchronized
    fun evaluate(result: DeviationResult, nowMs: Long): ReadinessVerdict {
        val previousNow = lastEvaluatedAtMs
        if (previousNow != null && nowMs < previousNow) reset()
        lastEvaluatedAtMs = nowMs

        val goal = result.goal ?: profile.goalFor(result.framing)
        val entryBlockers = CompositionReadiness.blockers(result, goal, 1f)
        val candidateReady = entryBlockers.isEmpty()

        if (inReady) {
            val exitBlockers = CompositionReadiness.blockers(result, goal, goal.readyExitFactor)
            val hardExitBlockers = exitBlockers - TRANSIENT_OBSERVATION_BLOCKERS
            if (hardExitBlockers.isEmpty() && exitBlockers.isEmpty()) {
                val stableFor = nowMs - (candidateSinceMs ?: nowMs)
                return ReadinessVerdict(true, candidateReady, emptySet(), stableFor, goal)
            }
            // keyframe 사이 predicted/held는 그 순간 셔터만 막고, 이미 검증한 안정 구간은 보존한다.
            // age가 상한을 넘으면 STALE이 hard blocker로 들어와 아래에서 완전히 리셋된다.
            if (hardExitBlockers.isEmpty()) {
                val stableFor = nowMs - (candidateSinceMs ?: nowMs)
                return ReadinessVerdict(false, false, exitBlockers, stableFor, goal)
            }
            inReady = false
            candidateSinceMs = null
        }

        if (!candidateReady) {
            val hardEntryBlockers = entryBlockers - TRANSIENT_OBSERVATION_BLOCKERS
            if (hardEntryBlockers.isNotEmpty()) candidateSinceMs = null
            return ReadinessVerdict(false, false, entryBlockers, 0L, goal)
        }

        val since = candidateSinceMs ?: nowMs.also { candidateSinceMs = it }
        val stableFor = (nowMs - since).coerceAtLeast(0L)
        if (stableFor >= goal.stabilityMs) {
            inReady = true
            return ReadinessVerdict(true, true, emptySet(), stableFor, goal)
        }
        return ReadinessVerdict(
            ready = false,
            candidateReady = true,
            blockers = setOf(ReadinessBlocker.UNSTABLE),
            stableForMs = stableFor,
            goal = goal,
        )
    }

    private companion object {
        val TRANSIENT_OBSERVATION_BLOCKERS = setOf(
            ReadinessBlocker.PREDICTED,
            ReadinessBlocker.HELD,
        )
    }
}
