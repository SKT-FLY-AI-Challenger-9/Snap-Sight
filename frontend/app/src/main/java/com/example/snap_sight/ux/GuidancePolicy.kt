package com.example.snap_sight.ux

import com.example.snap_sight.cv.DeviationResult
import com.example.snap_sight.cv.CanonicalReadinessEvaluator
import com.example.snap_sight.cv.CompositionProfile
import com.example.snap_sight.cv.ReadinessBlocker
import com.example.snap_sight.cv.ReadinessVerdict
import kotlin.math.abs

/**
 * ⑥ 안내 정책 — 판정([GuidanceState]) 스트림을 "언제 무엇을 재생할지"([GuidanceAction])로 바꾼다.
 * Android 의존성이 없어 시각(nowMs)을 주입받아 단위 테스트한다. 실제 TTS/진동/톤 재생은
 * [GuidanceFeedback] 이 담당한다.
 *
 * 2026-08-19 실사용 피드백 반영 (docs/ux/feedback-mapping.md 개정):
 *  - 위치 편차는 **방향 단어 음성**으로 안내한다 ("왼쪽으로" / "오른쪽으로" / "위로" / "아래로" /
 *    "가까이"). 한 번에 한 방향만 — 임계값 대비 가장 크게 벗어난 축을 고른다.
 *    **"뒤로"(FARTHER)는 말하지 않는다** — 시각장애 사용자에게 뒤로 이동은 위험하고, bbox 면적 기반
 *    거리 판단은 부정확하다 (2026-08-19 피드백). 너무 클 때의 처리는 후처리(크롭 등)로 넘긴다.
 *    같은 방향은 [DIRECTION_REPEAT_MS] 마다, 방향이 바뀌어도 [DIRECTION_MIN_GAP_MS] 안에는 다시 말하지 않는다.
 *  - 진동은 방향 음성과 **같은 순간에만** 짧게 1회 (매 판정 진동 → "진동이 너무 많다" 피드백).
 *  - LOST 는 [LOST_DEBOUNCE_MS] 이상 이어질 때만 **경고음**으로 알리고([LOST_TONE_INTERVAL_MS] 간격 반복),
 *    "피사체를 찾지 못했습니다" 음성은 [LOST_SPEAK_AFTER_MS] 이상 계속 못 찾을 때 1회만.
 *  - READY 는 [READY_DEBOUNCE_MS] 유지 시 "지금 촬영하세요" 1회(유지 중 반복 없음). READY 를 벗어났다
 *    다시 들어와도 [READY_RESPEAK_MS] 안에는 반복하지 않는다.
 *  - 수직(dy)은 [GuidanceState.isReady]와 canonical readiness에 항상 포함한다. 없거나 범위를
 *    벗어나면 "촬영하세요"로 승격하지 않는다.
 *  - `zoomHandlesDistance=true` 면 CLOSER(너무 작음)는 자동 줌인이 처리 중이므로 "가까이"를 말하지 않는다.
 *    다른 축이 다 맞으면 줌이 끝날 때까지 침묵한다. 줌 한계에 닿아 false 가 되면 그때 "가까이".
 *  - READY 히스테리시스: 한 번 READY 에 들어오면 편차가 임계값 × [GuidanceStateMapper.READY_EXIT_FACTOR] 를
 *    넘기 전까지는 READY 로 유지한다 (손떨림으로 READY ↔ 방향 안내가 튀지 않게).
 */
internal sealed interface GuidanceAction {
    data class Speak(val text: String) : GuidanceAction
    object Vibrate : GuidanceAction
    object WarningTone : GuidanceAction
}

// 노션 확정 스크립트(스크립트 - 준서) 문장 — SpeechCatalog 의 프리캐싱 음원과 1:1 로 일치해야 한다.
internal enum class GuidanceDirection(val utterance: String) {
    LEFT("조금 왼쪽으로 이동해 주세요."),
    RIGHT("조금 오른쪽으로 이동해 주세요."),
    UP("조금 위로 이동해 주세요."),
    DOWN("조금 아래로 이동해 주세요."),
    CLOSER("조금 가까이 가 주세요."),
    /** 정의만 남김 — [GuidancePolicy.pickDirection] 은 FARTHER 를 고르지 않는다. */
    FARTHER("조금 뒤로 당겨 주세요."),
}

/** 한 번의 canonical 평가에서 나온 최종 verdict와 렌더링 액션. */
internal data class GuidanceDecision(
    val verdict: ReadinessVerdict,
    val actions: List<GuidanceAction>,
)

internal class GuidancePolicy(
    private val readyUtterance: String = READY_UTTERANCE,
    private val lostUtterance: String = LOST_UTTERANCE,
    private val readinessEvaluator: CanonicalReadinessEvaluator = CanonicalReadinessEvaluator(),
) {
    private var lostSinceMs: Long? = null
    private var lastLostToneMs: Long = Long.MIN_VALUE / 2
    private var lostSpoken = false

    private var readySpokenAtMs: Long = Long.MIN_VALUE / 2
    private var readySpokenThisEpisode = false

    private var lastDirection: GuidanceDirection? = null
    private var lastDirectionAtMs: Long = Long.MIN_VALUE / 2
    private var lastReadyBlockedSpokenAtMs: Long = Long.MIN_VALUE / 2

    /**
     * 마지막으로 어떤 음성이든 말한 시각 — 하트비트([heartbeat]) 게이트.
     * 방향 단어가 나올 수 없는 상태(잘림, 자동 줌 처리 중, 안정화 대기)가
     * 이어지면 정책이 영원히 침묵했는데(2026-08-23 실사용: "중간에 길게 빈다"), 이때만
     * [HEARTBEAT_AFTER_MS] 간격으로 현재 상태를 짧게 말해 죽은 공백을 없앤다.
     */
    private var lastSpokenAtMs: Long = Long.MIN_VALUE / 2

    /** 하트비트 대상 상태에 처음 들어온 시각 — 진입 즉시가 아니라 지속될 때만 말하기 위한 기준. */
    private var heartbeatStateSinceMs: Long? = null

    /** 새 세션 시작 — 이전 세션의 "이미 말했음" 상태를 지운다. */
    @Synchronized
    fun reset() {
        lostSinceMs = null
        lastLostToneMs = Long.MIN_VALUE / 2
        lostSpoken = false
        readinessEvaluator.reset()
        readySpokenAtMs = Long.MIN_VALUE / 2
        readySpokenThisEpisode = false
        lastDirection = null
        lastDirectionAtMs = Long.MIN_VALUE / 2
        lastReadyBlockedSpokenAtMs = Long.MIN_VALUE / 2
        lastSpokenAtMs = Long.MIN_VALUE / 2
        heartbeatStateSinceMs = null
    }

    /**
     * @param readyBlockedReason null 이 아니면 구도가 READY 여도 "지금 촬영하세요" 대신
     *        이 사유를 말한다 (예: 셀카 모드에서 시선이 카메라를 벗어남 — "카메라를 봐 주세요").
     *        같은 사유는 [DIRECTION_REPEAT_MS] 간격으로만 반복한다.
     */
    fun onJudgment(
        state: GuidanceState,
        result: DeviationResult,
        nowMs: Long,
        zoomHandlesDistance: Boolean = false,
        readyBlockedReason: String? = null,
    ): List<GuidanceAction> = processJudgment(
        state = state,
        result = result,
        nowMs = nowMs,
        zoomHandlesDistance = zoomHandlesDistance,
        readyBlockedReason = readyBlockedReason,
    ).actions

    /** 액션과 UI가 같은 evaluator 호출의 verdict를 공유하도록 하는 canonical 진입점. */
    @Synchronized
    fun processJudgment(
        state: GuidanceState,
        result: DeviationResult,
        nowMs: Long,
        zoomHandlesDistance: Boolean = false,
        readyBlockedReason: String? = null,
    ): GuidanceDecision {
        val readiness = readinessEvaluator.evaluate(result, nowMs)
        fun decision(actions: List<GuidanceAction>) = GuidanceDecision(readiness, actions)
        if (!state.detected) return decision(onLost(nowMs))

        // 다시 찾음 — LOST 에피소드 종료
        lostSinceMs = null
        lostSpoken = false

        if (readiness.ready) {
            if (readyBlockedReason != null) {
                return decision(onReadyBlocked(readyBlockedReason, nowMs))
            }
            return decision(onReady(nowMs))
        }
        // 추가 게이트(시선 등)는 구도 안정화 중에도 즉시 알려준다.
        if (readiness.candidateReady && readyBlockedReason != null) {
            return decision(onReadyBlocked(readyBlockedReason, nowMs))
        }

        // 추정 bbox나 오래된 관측으로 사용자에게 이동을 요구하지 않는다. 이 출력은 오버레이를
        // 부드럽게 잇는 용도이며, 다음 detector keyframe에서 방향을 다시 확인한다.
        val observationUncertain = readiness.blockers.any {
            it == ReadinessBlocker.PREDICTED || it == ReadinessBlocker.HELD ||
                it == ReadinessBlocker.STALE
        }
        if (observationUncertain) {
            // PREDICTED/HELD alone is a short tracker gap and keeps the READY episode.
            // STALE or a simultaneous geometry/visibility blocker is a canonical hard exit;
            // after a fresh re-stabilization READY must be eligible to speak again.
            val hasHardExit = readiness.blockers.any {
                it != ReadinessBlocker.PREDICTED && it != ReadinessBlocker.HELD
            }
            if (hasHardExit) {
                readySpokenThisEpisode = false
                lastDirection = null
            }
            return decision(emptyList())
        }
        readySpokenThisEpisode = false
        val direction = pickDirection(state, result, zoomHandlesDistance)
        if (direction == null) {
            return decision(heartbeat(readiness, zoomHandlesDistance, nowMs))
        }
        heartbeatStateSinceMs = null
        val changed = direction != lastDirection
        val elapsed = nowMs - lastDirectionAtMs
        val due = if (changed) elapsed >= DIRECTION_MIN_GAP_MS else elapsed >= DIRECTION_REPEAT_MS
        if (!due) return decision(emptyList())
        lastDirection = direction
        lastDirectionAtMs = nowMs
        lastSpokenAtMs = nowMs
        return decision(listOf(GuidanceAction.Speak(direction.utterance), GuidanceAction.Vibrate))
    }

    /**
     * 방향 단어가 없는데 READY 도 아닌 "죽은 상태"의 주기적 상태 안내 (진동 없음).
     * 문구는 화면 카드([MainActivity.compositionGuidanceText])와 같은 표현을 쓴다 —
     * 눈으로 보는 사람과 듣는 사람이 같은 상태 설명을 받게.
     * 크기 초과(FARTHER)는 READY 를 막지 않으므로(CompositionReadiness, 2026-08-23)
     * 여기서 다루지 않는다.
     */
    private fun heartbeat(
        readiness: ReadinessVerdict,
        zoomHandlesDistance: Boolean,
        nowMs: Long,
    ): List<GuidanceAction> {
        val stateSince = heartbeatStateSinceMs ?: nowMs.also { heartbeatStateSinceMs = it }
        // 진입 즉시가 아니라 "상태가 지속되고 + 다른 음성도 없는" 공백에서만 말한다 —
        // 세션 시작 안내나 방금 나온 방향 단어를 하트비트가 자르지 않게.
        if (nowMs - stateSince < HEARTBEAT_AFTER_MS) return emptyList()
        if (nowMs - lastSpokenAtMs < HEARTBEAT_AFTER_MS) return emptyList()
        val text = when {
            ReadinessBlocker.VISIBILITY in readiness.blockers -> VISIBILITY_HEARTBEAT
            ReadinessBlocker.SIZE in readiness.blockers && zoomHandlesDistance -> AUTO_ZOOM_HEARTBEAT
            ReadinessBlocker.UNSTABLE in readiness.blockers -> HOLD_STEADY_HEARTBEAT
            else -> return emptyList()
        }
        lastSpokenAtMs = nowMs
        return listOf(GuidanceAction.Speak(text))
    }

    private fun onLost(nowMs: Long): List<GuidanceAction> {
        readySpokenThisEpisode = false
        lastDirection = null // 다시 찾으면 방향을 바로 말해준다
        heartbeatStateSinceMs = null
        val since = lostSinceMs ?: run { lostSinceMs = nowMs; return emptyList() }
        if (nowMs - since < LOST_DEBOUNCE_MS) return emptyList()

        val actions = ArrayList<GuidanceAction>(2)
        if (nowMs - lastLostToneMs >= LOST_TONE_INTERVAL_MS) {
            lastLostToneMs = nowMs
            actions.add(GuidanceAction.WarningTone)
        }
        if (!lostSpoken && nowMs - since >= LOST_SPEAK_AFTER_MS) {
            lostSpoken = true
            lastSpokenAtMs = nowMs
            actions.add(GuidanceAction.Speak(lostUtterance))
        }
        return actions
    }

    /**
     * 구도는 READY 인데 추가 조건(시선 등)이 안 맞음 — "지금 촬영하세요" 대신 사유를 말한다.
     * READY 에피소드는 유지해(hysteresis) 방향 안내로 튀지 않게 하고, 사유만 주기적으로 반복.
     */
    private fun onReadyBlocked(reason: String, nowMs: Long): List<GuidanceAction> {
        lastDirection = null
        heartbeatStateSinceMs = null
        if (nowMs - lastReadyBlockedSpokenAtMs < DIRECTION_REPEAT_MS) return emptyList()
        lastReadyBlockedSpokenAtMs = nowMs
        lastSpokenAtMs = nowMs
        return listOf(GuidanceAction.Speak(reason))
    }

    private fun onReady(nowMs: Long): List<GuidanceAction> {
        lastDirection = null
        heartbeatStateSinceMs = null
        if (readySpokenThisEpisode) return emptyList()
        if (nowMs - readySpokenAtMs < READY_RESPEAK_MS) return emptyList()
        readySpokenAtMs = nowMs
        readySpokenThisEpisode = true
        lastSpokenAtMs = nowMs
        return listOf(GuidanceAction.Speak(readyUtterance))
    }

    companion object {
        // 노션 확정 스크립트 문장 (5-1 READY / 4-9 이탈) — SpeechCatalog 프리캐싱 음원과 일치
        const val READY_UTTERANCE = "좋아요. 촬영할 수 있어요."
        const val LOST_UTTERANCE = "피사체가 화면에서 벗어났어요. 다시 찾을게요."

        const val DIRECTION_REPEAT_MS = 2_500L
        const val DIRECTION_MIN_GAP_MS = 1_000L
        const val LOST_DEBOUNCE_MS = 800L
        const val LOST_TONE_INTERVAL_MS = 3_000L
        const val LOST_SPEAK_AFTER_MS = 6_000L
        const val READY_DEBOUNCE_MS = 300L
        const val READY_RESPEAK_MS = 3_000L

        /** 어떤 음성도 없는 상태가 이만큼 이어지면 하트비트 상태 안내를 1회 말한다 (반복도 이 간격). */
        const val HEARTBEAT_AFTER_MS = 4_000L
        const val VISIBILITY_HEARTBEAT = "피사체 전체가 화면 안에 들어오게 비춰주세요"
        const val AUTO_ZOOM_HEARTBEAT = "구도를 자동으로 맞추는 중이에요"
        const val HOLD_STEADY_HEARTBEAT = "좋아요, 그대로 유지해주세요"

        /**
         * 벗어난 축 중 "임계값 대비 비율"이 가장 큰 축 하나를 고른다.
         * 수직(dy)은 [GuidanceState.vertical] 이 있을 때만 후보다.
         */
        fun pickDirection(
            state: GuidanceState,
            result: DeviationResult,
            zoomHandlesDistance: Boolean = false,
        ): GuidanceDirection? {
            if (!state.detected) return null
            var best: GuidanceDirection? = null
            var bestScore = 0f
            fun consider(direction: GuidanceDirection?, score: Float) {
                if (direction != null && score > bestScore) {
                    best = direction
                    bestScore = score
                }
            }
            val x = result.xDeviation ?: 0f
            val y = result.yDeviation ?: 0f
            val size = result.sizeDeviation ?: 0f
            val goal = result.goal ?: CompositionProfile.DEFAULT.goalFor(result.framing)
            consider(
                when (state.horizontal) {
                    HorizontalAlignment.LEFT -> GuidanceDirection.LEFT
                    HorizontalAlignment.RIGHT -> GuidanceDirection.RIGHT
                    else -> null
                },
                abs(x) / goal.maxAbsXDeviation.coerceAtLeast(1e-6f),
            )
            consider(
                when (state.vertical) {
                    VerticalAlignment.UP -> GuidanceDirection.UP
                    VerticalAlignment.DOWN -> GuidanceDirection.DOWN
                    else -> null
                },
                abs(y) / goal.maxAbsYDeviation.coerceAtLeast(1e-6f),
            )
            consider(
                when (state.distance) {
                    DistanceAlignment.CLOSER -> if (zoomHandlesDistance) null else GuidanceDirection.CLOSER
                    DistanceAlignment.FARTHER -> null // "뒤로"는 안내하지 않는다 (KDoc 참고)
                    else -> null
                },
                abs(size) / goal.maxAbsAreaDeviation.coerceAtLeast(1e-6f),
            )
            return best
        }
    }
}
