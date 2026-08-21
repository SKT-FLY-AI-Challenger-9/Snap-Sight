package com.example.snap_sight.cv

/**
 * 타겟 락([SpecDeviationCalculator]) 파라미터.
 * `docs/feature-expansion-plan.md` 기능 1-B — 값 조정은 여기 한 곳에서만 한다.
 */
data class TargetLockConfig(
    /**
     * 타겟 track 이 끊긴 뒤, 새 track 을 "같은 타겟"으로 재획득해도 되는 시간 창(ms).
     * tracker 의 ID 가 바뀌어도(churn) 물리적으로 같은 대상이면 근처에서 다시 나타난다.
     */
    val reacquireWindowMs: Long = 1_500L,
    /** 재획득으로 인정할 최소 IoU (직전 타겟 bbox 대비). */
    val reacquireMinIou: Float = 0.10f,
    /** IoU 가 0 이어도(빠른 이동) 중심 거리(normalized)가 이 이하면 재획득으로 인정. */
    val reacquireMaxCenterDistance: Float = 0.35f,
    /**
     * 관측이 잠깐 끊겼을 때 직전 편차를 유지(hold)하는 시간(ms).
     * 검출 깜빡임 1~2 프레임에 LOST 안내·자동 줌 리셋이 튀지 않게 브리지한다.
     */
    val holdMs: Long = 400L,
) {
    init {
        require(reacquireWindowMs >= 0) { "reacquireWindowMs must be non-negative" }
        require(reacquireMinIou in 0f..1f) { "reacquireMinIou must be in [0, 1]" }
        require(reacquireMaxCenterDistance in 0f..2f) { "reacquireMaxCenterDistance must be in [0, 2]" }
        require(holdMs >= 0) { "holdMs must be non-negative" }
    }
}

/**
 * 트래킹 안정성 지표 (기능 1-A). 세션 종료 시 로그로 남겨 캘리브레이션 근거로 쓴다.
 *
 * @property targetSwitches   타겟이 (재획득이 아닌) 다른 후보로 갈아탄 횟수
 * @property reacquisitions   track_id 는 바뀌었지만 공간·라벨 매칭으로 같은 타겟으로 이어붙인 횟수
 * @property lostEpisodes     hold 로도 못 버티고 실제 LOST(null) 로 넘어간 에피소드 수
 * @property heldFrames       직전 편차를 유지(hold)한 호출 수
 */
data class TargetLockStats(
    val targetSwitches: Int = 0,
    val reacquisitions: Int = 0,
    val lostEpisodes: Int = 0,
    val heldFrames: Int = 0,
)

/**
 * [DeviationCalculator] 확장 자리(②의 `Deviation.kt`)의 첫 실구현 + **타겟 락**(기능 1-B).
 *
 * tracking·선택이 끝난 프레임에서 타겟 1개를 골라 순수 기하 편차([FramingDeviation])를
 * 계산한다. 파이프라인에 꽂히는 지점은 [SnapSightFrameProcessor.create] 의
 * `deviationCalculator` 파라미터이고, 결과는 [CvFrameOutput.deviation] 으로 흘러나온다.
 *
 * 타겟 선택 규칙 (2026-08-21 개정 — 타겟 락, `docs/feature-expansion-plan.md` 기능 1-B):
 *  - landscape 의도는 겨냥할 피사체가 없음 → null (docs/deviation-interface.md 의 "대상 없음" 규약)
 *  - **직전 프레임의 타겟(track_id)이 아직 후보에 있으면 그대로 유지**(sticky)
 *  - sticky 가 끊겼으면, [TargetLockConfig.reacquireWindowMs] 안에서 직전 타겟과
 *    **같은 라벨 + 근접 위치**(IoU/중심 거리)의 새 track 을 같은 타겟으로 재획득한다 —
 *    tracker ID churn 이 사용자에게 LOST 로 새지 않게 하는 층
 *  - 재획득도 안 되면 [TargetLockConfig.holdMs] 동안 직전 편차를 유지(hold, `held=true`)해
 *    깜빡임을 브리지하고, 그마저 지나면 **면적이 가장 큰** 후보로 갈아탄다 (기존 규칙)
 *  - 후보가 아예 없으면 hold → 만료 후 null (= 타겟 유실, LOST 후보)
 *  - [reset] 은 새 세션 시작 시 호출 — track_id 가 1부터 다시 시작하므로 이전 기억을 지운다
 *
 * @param clock 테스트용 시각 주입 (기본 [System.currentTimeMillis]).
 */
class SpecDeviationCalculator(
    private val lockConfig: TargetLockConfig = TargetLockConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviationCalculator {

    private val stateLock = Any()
    private var stickyTrackId: Int? = null
    private var lastTarget: TrackedObject? = null
    private var lastTargetAtMs: Long = 0L
    private var lastDeviation: FramingDeviation? = null
    private var inLostEpisode = false

    private var targetSwitches = 0
    private var reacquisitions = 0
    private var lostEpisodes = 0
    private var heldFrames = 0

    /** 새 촬영 세션 — 이전 세션의 타겟 기억과 지표를 지운다 (track_id 재시작). */
    fun reset() {
        synchronized(stateLock) {
            stickyTrackId = null
            lastTarget = null
            lastTargetAtMs = 0L
            lastDeviation = null
            inLostEpisode = false
            targetSwitches = 0
            reacquisitions = 0
            lostEpisodes = 0
            heldFrames = 0
        }
    }

    /** 현재 세션의 안정성 지표 스냅샷. [reset] 전에 읽어 세션 종료 로그로 남긴다. */
    fun stats(): TargetLockStats = synchronized(stateLock) {
        TargetLockStats(targetSwitches, reacquisitions, lostEpisodes, heldFrames)
    }

    override fun compute(selection: TargetSelection, spec: TargetSpec?): FramingDeviation? {
        if (spec?.subjectType == TargetSpec.SubjectType.LANDSCAPE) return null
        val now = clock()
        synchronized(stateLock) {
            val target = pickTarget(selection.candidates, now) ?: return holdOrLost(now)
            inLostEpisode = false
            stickyTrackId = target.trackId
            lastTarget = target
            lastTargetAtMs = now
            val deviation = FramingDeviation(
                trackId = target.trackId,
                offsetX = ((target.bbox.centerX - 0.5f) * 2f).coerceIn(-1f, 1f),
                offsetY = ((target.bbox.centerY - 0.5f) * 2f).coerceIn(-1f, 1f),
                areaRatio = target.bbox.area.coerceIn(0f, 1f),
            )
            lastDeviation = deviation
            return deviation
        }
    }

    /** 후보 중에서 이번 프레임의 타겟을 고른다. hold 해야 하면 null (호출부에서 [holdOrLost]). */
    private fun pickTarget(candidates: List<TrackedObject>, now: Long): TrackedObject? {
        if (candidates.isEmpty()) return null

        stickyTrackId?.let { sticky ->
            candidates.firstOrNull { it.trackId == sticky }?.let { return it }
        }

        val remembered = lastTarget
        if (remembered != null && now - lastTargetAtMs <= lockConfig.reacquireWindowMs) {
            reacquire(candidates, remembered)?.let {
                reacquisitions++
                return it
            }
            // 재획득 후보가 없으면 hold 시간 동안은 엉뚱한 후보로 점프하지 않는다
            if (now - lastTargetAtMs <= lockConfig.holdMs) return null
        }

        val largest = candidates.maxWithOrNull(
            compareBy<TrackedObject> { it.bbox.area }.thenByDescending { it.trackId }
        )
        if (largest != null && remembered != null && largest.trackId != remembered.trackId) {
            targetSwitches++
        }
        return largest
    }

    /** 직전 타겟과 같은 라벨 + 근접 위치의 후보를 찾는다. 없으면 null. */
    private fun reacquire(candidates: List<TrackedObject>, remembered: TrackedObject): TrackedObject? {
        val sameLabel = candidates.filter {
            it.label.trim().equals(remembered.label.trim(), ignoreCase = true)
        }
        // 라벨이 전부 다르면(모델 라벨 흔들림) 위치만으로 판단한다
        val pool = sameLabel.ifEmpty { candidates }
        return pool
            .map { candidate -> candidate to spatialScore(candidate.bbox, remembered.bbox) }
            .filter { (_, score) -> score > 0f }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    /** 재획득 적합도. 0 이하 = 부적합. IoU 우선, IoU 0 이면 중심 거리로 보조 판정. */
    private fun spatialScore(candidate: BoundingBox, remembered: BoundingBox): Float {
        val iou = candidate.iou(remembered)
        if (iou >= lockConfig.reacquireMinIou) return 1f + iou
        val dx = candidate.centerX - remembered.centerX
        val dy = candidate.centerY - remembered.centerY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (distance <= lockConfig.reacquireMaxCenterDistance) {
            return 1f - distance / lockConfig.reacquireMaxCenterDistance.coerceAtLeast(1e-6f)
        }
        return 0f
    }

    /** 관측 없음 — hold 가 유효하면 직전 편차를 유지, 아니면 LOST(null). */
    private fun holdOrLost(now: Long): FramingDeviation? {
        val previous = lastDeviation
        if (previous != null && lastTarget != null && now - lastTargetAtMs <= lockConfig.holdMs) {
            heldFrames++
            return previous.copy(held = true)
        }
        if (previous != null && !inLostEpisode) {
            inLostEpisode = true
            lostEpisodes++
        }
        return null
    }
}

/**
 * 기하 편차([FramingDeviation]) → 판정 편차([DeviationResult]) 해석.
 *
 * `backend/judgment/deviation.py`(PR #27) / `docs/deviation-interface.md` 계약의 Kotlin
 * 이식이며, 이 파일이 런타임 정본이다 (실시간 판정은 온디바이스 — Notion 파이프라인 ④).
 *
 * 부호 규약 (계약 문서와 동일):
 *  - [DeviationResult.xDeviation] = center_x − 0.5 (−0.5..+0.5). 음수 = 타겟이 왼쪽, 양수 = 오른쪽
 *  - [DeviationResult.sizeDeviation] = area_ratio − 프레이밍별 목표비. 음수 = 너무 멂, 양수 = 너무 가까움
 */
object DeviationJudgment {

    /**
     * 프레이밍별 목표 면적비 — 실측 검증 전 1차 추정치.
     * `docs/deviation-interface.md` 의 값과 반드시 일치시킨다 (테스트로 고정).
     */
    val TARGET_AREA_RATIO: Map<TargetSpec.Framing, Float> = mapOf(
        TargetSpec.Framing.CLOSEUP to 0.30f,
        TargetSpec.Framing.FULL_BODY to 0.12f,
        TargetSpec.Framing.WIDE to 0.04f,
    )

    // READY(촬영 가능) 후보 판정 임계값 — 이슈 #42 실기기 편차 분포로 1차 캘리브레이션(0.15/0.10)된 뒤,
    // 2026-08-19 실사용 피드백("기준이 너무 빡세 위치 조정을 계속 해야 함")으로 x 를 0.20 으로 완화.
    // 정본은 ⑥의 docs/ux/guidance-state-schema.md (CENTERED 허용 오차)이며 그 값에 맞춘다.
    const val READY_MAX_ABS_X_DEVIATION = 0.20f
    const val READY_MAX_ABS_SIZE_DEVIATION = 0.10f

    /**
     * @param deviation 파이프라인이 계산한 기하 편차. null = 겨냥할 대상 없음
     *                  (타겟 유실과 landscape 의도 모두 포함 — LOST 후보)
     * @param framing   의도 프레이밍. 의도 없는 세션은 기본값 FULL_BODY 로 판정
     */
    fun judge(deviation: FramingDeviation?, framing: TargetSpec.Framing): DeviationResult {
        if (deviation == null) {
            return DeviationResult(subjectDetected = false, xDeviation = null, sizeDeviation = null)
        }
        return DeviationResult(
            subjectDetected = true,
            // FramingDeviation.offsetX 는 -1..1 스케일 → 계약(center_x − 0.5)의 -0.5..0.5 로 환산
            xDeviation = deviation.offsetX / 2f,
            sizeDeviation = deviation.areaRatio - TARGET_AREA_RATIO.getValue(framing),
            // 수직 편차(dy)는 계약상 "반영 여부 미확정"이라 additive 로만 싣는다 — 같은 -0.5..0.5 스케일.
            // READY 판정에는 쓰지 않고 ⑥ 방향 음성("위/아래")에만 쓴다.
            yDeviation = deviation.offsetY / 2f,
        )
    }

    /** 두 편차가 모두 임계값 안 = READY 후보. 최종 READY 판정·자동 셔터는 후속 이슈. */
    fun isReadyCandidate(result: DeviationResult): Boolean {
        val x = result.xDeviation ?: return false
        val size = result.sizeDeviation ?: return false
        return kotlin.math.abs(x) <= READY_MAX_ABS_X_DEVIATION &&
            kotlin.math.abs(size) <= READY_MAX_ABS_SIZE_DEVIATION
    }
}

/**
 * 판정 편차 결과. [subjectDetected] 가 false 면 두 편차는 반드시 null,
 * true 면 반드시 채워져 있다 — 계약 위반은 생성 시점에 막는다.
 *
 * [yDeviation] 은 additive(선택) 필드다: 수직 편차 반영이 계약상 미확정이라 READY 판정에는
 * 쓰지 않으며, ⑥이 "위/아래" 방향 안내에만 쓴다. 없으면 null.
 */
data class DeviationResult(
    val subjectDetected: Boolean,
    val xDeviation: Float?,
    val sizeDeviation: Float?,
    val yDeviation: Float? = null,
) {
    init {
        if (subjectDetected) {
            require(xDeviation != null && sizeDeviation != null) {
                "subjectDetected=true 인 경우 편차 값은 비어 있으면 안 됩니다."
            }
        } else {
            require(xDeviation == null && sizeDeviation == null && yDeviation == null) {
                "subjectDetected=false 인 경우 편차 값은 모두 비어 있어야 합니다."
            }
        }
    }
}

/** 판정 편차 수신 계약 (⑥ 연결 지점). CV 분석 스레드에서 호출될 수 있다. */
fun interface DeviationListener {
    fun onDeviation(result: DeviationResult)
}
