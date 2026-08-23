package com.example.snap_sight.cv

import kotlin.math.roundToLong

/** detector keyframe 빈도를 target 상태에 맞춰 바꾸는 순수 시간 기반 scheduler. */
enum class DetectionCadenceState { SEARCHING, LOCKED, LOST }

data class AdaptiveDetectionConfig(
    val enabled: Boolean = true,
    /** 아직 한 번도 target을 잡지 못한 동안의 기준 주기 배수. */
    val searchingIntervalMultiplier: Float = 1f,
    /** fresh target을 유지 중일 때 detector 부하를 줄이는 기준 주기 배수. */
    val lockedIntervalMultiplier: Float = 2f,
    /** lock을 잃은 뒤 빠르게 재탐색하는 기준 주기 배수. */
    val lostIntervalMultiplier: Float = 0.65f,
    /** 마지막 fresh target 이후 이 시간까지는 짧은 miss로 보고 LOCKED cadence를 유지한다. */
    val lostAfterMs: Long = 450L,
    /** adaptive mode에서 detector가 과도하게 호출되지 않도록 하는 절대 하한. */
    val minimumIntervalMs: Long = 75L,
    val maximumIntervalMs: Long = 1_000L,
) {
    init {
        for ((name, value) in listOf(
            "searchingIntervalMultiplier" to searchingIntervalMultiplier,
            "lockedIntervalMultiplier" to lockedIntervalMultiplier,
            "lostIntervalMultiplier" to lostIntervalMultiplier,
        )) {
            require(value.isFinite() && value > 0f) { "$name must be positive and finite" }
        }
        require(lostAfterMs >= 0L) { "lostAfterMs must be non-negative" }
        require(minimumIntervalMs >= 0L) { "minimumIntervalMs must be non-negative" }
        require(maximumIntervalMs >= minimumIntervalMs) {
            "maximumIntervalMs must be at least minimumIntervalMs"
        }
    }
}

/**
 * SEARCHING → LOCKED → LOST 상태와 detector keyframe 간격의 단일 정본.
 * 카메라 FPS/드롭 수가 달라도 같은 wall-clock cadence를 내도록 frame count를 사용하지 않는다.
 */
class AdaptiveDetectionScheduler(
    private val config: AdaptiveDetectionConfig = AdaptiveDetectionConfig(),
) {
    var state: DetectionCadenceState = DetectionCadenceState.SEARCHING
        private set

    private var everLocked = false
    private var lastFreshTargetAtMs: Long? = null

    @Synchronized
    fun reset() {
        state = DetectionCadenceState.SEARCHING
        everLocked = false
        lastFreshTargetAtMs = null
    }

    /** detector가 실제 실행된 프레임의 target 선택 결과를 기록한다. */
    @Synchronized
    fun onDetectorResult(selection: TargetSelection?, nowMs: Long) {
        val stateCanLock = selection?.state == TargetSelectionState.SELECTED ||
            selection?.state == TargetSelectionState.DISABLED
        val hasFreshTarget = stateCanLock && selection.candidates.any {
            it.freshness == ObservationFreshness.FRESH
        }
        if (hasFreshTarget) {
            everLocked = true
            lastFreshTargetAtMs = nowMs
            state = DetectionCadenceState.LOCKED
        } else {
            refreshState(nowMs)
        }
    }

    /** 현재 상태와 thermal 배수를 반영한 다음 detector 실행 간격. 0 기준은 기존 매-frame 동작. */
    @Synchronized
    fun intervalMs(baseIntervalMs: Long, thermalSlowdown: Float = 1f, nowMs: Long): Long {
        require(baseIntervalMs >= 0L) { "baseIntervalMs must be non-negative" }
        if (baseIntervalMs == 0L) return 0L
        refreshState(nowMs)
        val thermal = thermalSlowdown.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        val multiplier = if (!config.enabled) 1f else when (state) {
            DetectionCadenceState.SEARCHING -> config.searchingIntervalMultiplier
            DetectionCadenceState.LOCKED -> config.lockedIntervalMultiplier
            DetectionCadenceState.LOST -> config.lostIntervalMultiplier
        }
        val candidate = (baseIntervalMs.toDouble() * thermal * multiplier).roundToLong()
        return if (config.enabled) {
            candidate.coerceIn(config.minimumIntervalMs, config.maximumIntervalMs)
        } else {
            candidate.coerceAtLeast(0L)
        }
    }

    @Synchronized
    fun shouldRunDetector(
        nowMs: Long,
        lastDetectorAtMs: Long?,
        baseIntervalMs: Long,
        thermalSlowdown: Float = 1f,
    ): Boolean {
        val previous = lastDetectorAtMs ?: return true
        if (nowMs < previous) return true // monotonic clock 교체/리셋 시 즉시 복구
        return nowMs - previous >= intervalMs(baseIntervalMs, thermalSlowdown, nowMs)
    }

    private fun refreshState(nowMs: Long) {
        if (!everLocked) {
            state = DetectionCadenceState.SEARCHING
            return
        }
        val lastFresh = lastFreshTargetAtMs
        state = if (lastFresh == null || nowMs - lastFresh >= config.lostAfterMs) {
            DetectionCadenceState.LOST
        } else {
            DetectionCadenceState.LOCKED
        }
    }
}
