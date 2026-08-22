package com.example.snap_sight.ux

import com.example.snap_sight.cv.DeviationResult
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
 *  - 수직(dy)은 계약상 [GuidanceState.isReady] 에 포함되지 않지만, 안내 정책에서는 위/아래로 벗어나 있으면
 *    "촬영하세요" 대신 "위로/아래로" 를 말한다 (모순된 안내 방지).
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

internal enum class GuidanceDirection(val utterance: String) {
    LEFT("왼쪽으로"),
    RIGHT("오른쪽으로"),
    UP("위로"),
    DOWN("아래로"),
    CLOSER("가까이"),
    /** 정의만 남김 — [GuidancePolicy.pickDirection] 은 FARTHER 를 고르지 않는다. */
    FARTHER("뒤로"),
}

internal class GuidancePolicy(
    private val readyUtterance: String = READY_UTTERANCE,
    private val lostUtterance: String = LOST_UTTERANCE,
) {
    private var lostSinceMs: Long? = null
    private var lastLostToneMs: Long = Long.MIN_VALUE / 2
    private var lostSpoken = false

    private var readySinceMs: Long? = null
    private var readySpokenAtMs: Long = Long.MIN_VALUE / 2
    private var readySpokenThisEpisode = false

    private var lastDirection: GuidanceDirection? = null
    private var lastDirectionAtMs: Long = Long.MIN_VALUE / 2
    private var lastReadyBlockedSpokenAtMs: Long = Long.MIN_VALUE / 2

    /** 새 세션 시작 — 이전 세션의 "이미 말했음" 상태를 지운다. */
    fun reset() {
        lostSinceMs = null
        lastLostToneMs = Long.MIN_VALUE / 2
        lostSpoken = false
        readySinceMs = null
        readySpokenAtMs = Long.MIN_VALUE / 2
        readySpokenThisEpisode = false
        lastDirection = null
        lastDirectionAtMs = Long.MIN_VALUE / 2
        lastReadyBlockedSpokenAtMs = Long.MIN_VALUE / 2
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
    ): List<GuidanceAction> {
        if (!state.detected) return onLost(nowMs)

        // 다시 찾음 — LOST 에피소드 종료
        lostSinceMs = null
        lostSpoken = false

        if (isReadyWithHysteresis(state, result)) {
            if (readyBlockedReason != null) return onReadyBlocked(readyBlockedReason, nowMs)
            return onReady(nowMs)
        }

        readySinceMs = null
        readySpokenThisEpisode = false
        val direction = pickDirection(state, result, zoomHandlesDistance) ?: return emptyList()
        val changed = direction != lastDirection
        val elapsed = nowMs - lastDirectionAtMs
        val due = if (changed) elapsed >= DIRECTION_MIN_GAP_MS else elapsed >= DIRECTION_REPEAT_MS
        if (!due) return emptyList()
        lastDirection = direction
        lastDirectionAtMs = nowMs
        return listOf(GuidanceAction.Speak(direction.utterance), GuidanceAction.Vibrate)
    }

    private fun onLost(nowMs: Long): List<GuidanceAction> {
        readySinceMs = null
        readySpokenThisEpisode = false
        lastDirection = null // 다시 찾으면 방향을 바로 말해준다
        val since = lostSinceMs ?: run { lostSinceMs = nowMs; return emptyList() }
        if (nowMs - since < LOST_DEBOUNCE_MS) return emptyList()

        val actions = ArrayList<GuidanceAction>(2)
        if (nowMs - lastLostToneMs >= LOST_TONE_INTERVAL_MS) {
            lastLostToneMs = nowMs
            actions.add(GuidanceAction.WarningTone)
        }
        if (!lostSpoken && nowMs - since >= LOST_SPEAK_AFTER_MS) {
            lostSpoken = true
            actions.add(GuidanceAction.Speak(lostUtterance))
        }
        return actions
    }

    /**
     * 진입: 계약대로 x·size CENTERED(+ 수직이 있으면 위/아래로 벗어나지 않음).
     * 유지: 이미 READY 에피소드 중이면 각 편차가 임계값 × READY_EXIT_FACTOR 안이면 계속 READY.
     */
    private fun isReadyWithHysteresis(state: GuidanceState, result: DeviationResult): Boolean {
        val enter = state.isReady &&
            state.vertical != VerticalAlignment.UP && state.vertical != VerticalAlignment.DOWN
        if (enter) return true
        if (readySinceMs == null) return false
        val f = GuidanceStateMapper.READY_EXIT_FACTOR
        val x = result.xDeviation ?: return false
        val size = result.sizeDeviation ?: return false
        val y = result.yDeviation ?: 0f
        return abs(x) <= GuidanceStateMapper.MAX_ABS_X_DEVIATION * f &&
            abs(size) <= GuidanceStateMapper.MAX_ABS_SIZE_DEVIATION * f &&
            abs(y) <= GuidanceStateMapper.MAX_ABS_Y_DEVIATION * f
    }

    /**
     * 구도는 READY 인데 추가 조건(시선 등)이 안 맞음 — "지금 촬영하세요" 대신 사유를 말한다.
     * READY 에피소드는 유지해(hysteresis) 방향 안내로 튀지 않게 하고, 사유만 주기적으로 반복.
     */
    private fun onReadyBlocked(reason: String, nowMs: Long): List<GuidanceAction> {
        lastDirection = null
        if (readySinceMs == null) readySinceMs = nowMs
        if (nowMs - lastReadyBlockedSpokenAtMs < DIRECTION_REPEAT_MS) return emptyList()
        lastReadyBlockedSpokenAtMs = nowMs
        return listOf(GuidanceAction.Speak(reason))
    }

    private fun onReady(nowMs: Long): List<GuidanceAction> {
        lastDirection = null
        val since = readySinceMs ?: run { readySinceMs = nowMs; nowMs }
        if (readySpokenThisEpisode) return emptyList()
        if (nowMs - since < READY_DEBOUNCE_MS) return emptyList()
        if (nowMs - readySpokenAtMs < READY_RESPEAK_MS) return emptyList()
        readySpokenAtMs = nowMs
        readySpokenThisEpisode = true
        return listOf(GuidanceAction.Speak(readyUtterance))
    }

    companion object {
        const val READY_UTTERANCE = "지금 촬영하세요"
        const val LOST_UTTERANCE = "피사체를 찾지 못했습니다"

        const val DIRECTION_REPEAT_MS = 2_500L
        const val DIRECTION_MIN_GAP_MS = 1_000L
        const val LOST_DEBOUNCE_MS = 800L
        const val LOST_TONE_INTERVAL_MS = 3_000L
        const val LOST_SPEAK_AFTER_MS = 6_000L
        const val READY_DEBOUNCE_MS = 300L
        const val READY_RESPEAK_MS = 3_000L

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
            consider(
                when (state.horizontal) {
                    HorizontalAlignment.LEFT -> GuidanceDirection.LEFT
                    HorizontalAlignment.RIGHT -> GuidanceDirection.RIGHT
                    else -> null
                },
                abs(x) / GuidanceStateMapper.MAX_ABS_X_DEVIATION,
            )
            consider(
                when (state.vertical) {
                    VerticalAlignment.UP -> GuidanceDirection.UP
                    VerticalAlignment.DOWN -> GuidanceDirection.DOWN
                    else -> null
                },
                abs(y) / GuidanceStateMapper.MAX_ABS_Y_DEVIATION,
            )
            consider(
                when (state.distance) {
                    DistanceAlignment.CLOSER -> if (zoomHandlesDistance) null else GuidanceDirection.CLOSER
                    DistanceAlignment.FARTHER -> null // "뒤로"는 안내하지 않는다 (KDoc 참고)
                    else -> null
                },
                abs(size) / GuidanceStateMapper.MAX_ABS_SIZE_DEVIATION,
            )
            return best
        }
    }
}
