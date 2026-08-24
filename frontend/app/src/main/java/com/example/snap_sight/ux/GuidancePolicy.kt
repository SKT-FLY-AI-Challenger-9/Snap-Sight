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
    /** 지정 피사체가 화면에 잡혀 있는 동안의 연속 진동 시작/정지 (사용자 요청 2026-08-24). */
    object PresenceVibrationStart : GuidanceAction
    object PresenceVibrationStop : GuidanceAction
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

    // 음식 피치 (2026-08-25) — 음식 세션에서 폰 각도를 목표(45°)로 유도. CV 편차가 아니라
    // 기울기 센서에서 오므로 pickDirection 이 아닌 judge() 의 피치 분기가 고른다.
    TILT_LAY("폰을 조금 더 눕혀 주세요."),
    TILT_RAISE("폰을 조금 세워 주세요."),
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

    // ---- 탐색 안내 (노션 스크립트 상태 3) — 첫 검출 전의 "찾는 중" 단계 ----
    /** 이번 세션에서 피사체를 한 번이라도 검출했는가 — 전이면 탐색 안내, 후면 LOST 정책. */
    private var subjectEverDetected = false
    /** 탐색(무검출) 시작 시각 — t2/t3 안내 타이밍 기준. */
    private var searchingSinceMs: Long? = null
    private var searchHintSpoken = false
    private var searchFailSpoken = false
    /** 안내 문장에 넣을 피사체 이름 (예: "강아지") — 세션마다 [setSubject]로 갱신. */
    private var subjectWord: String = DEFAULT_SUBJECT

    /**
     * 발화로 대상이 실제 지정됐는가 — 지정 없이는 "찾았어요"류 안내와 존재 진동을 내지 않는다
     * (사용자 요청 2026-08-24: 아무 물체나 잡혔다고 "찾았다"고 말하지 않기).
     */
    private var subjectDesignated = false

    // ---- 존재 확인 진동 — 피사체가 잡혀 있는 동안 연속 햅틱 (사용자 요청 2026-08-24) ----
    private var presenceSinceMs: Long? = null
    private var presenceVibrationOn = false

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
        subjectEverDetected = false
        searchingSinceMs = null
        searchHintSpoken = false
        searchFailSpoken = false
        subjectWord = DEFAULT_SUBJECT
        subjectDesignated = false
        // 진동 정지는 액션으로 못 내보내므로 GuidanceFeedback.resetSession 이 직접 끈다
        presenceSinceMs = null
        presenceVibrationOn = false
    }

    /**
     * 이번 세션의 피사체 이름 — 발화·스펙이 해석되는 대로 호출한다.
     * null/공백이면 "지정 없음"으로 보고 찾았어요류 안내·존재 진동을 잠근다.
     */
    @Synchronized
    fun setSubject(word: String?) {
        val trimmed = word?.trim()?.takeIf { it.isNotEmpty() }
        subjectDesignated = trimmed != null
        subjectWord = trimmed ?: DEFAULT_SUBJECT
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
        pitchDeviationDeg: Float? = null,
    ): List<GuidanceAction> = processJudgment(
        state = state,
        result = result,
        nowMs = nowMs,
        zoomHandlesDistance = zoomHandlesDistance,
        readyBlockedReason = readyBlockedReason,
        pitchDeviationDeg = pitchDeviationDeg,
    ).actions

    /**
     * 액션과 UI가 같은 evaluator 호출의 verdict를 공유하도록 하는 canonical 진입점.
     *
     * @param pitchDeviationDeg 음식 세션의 폰 각도 편차 (목표각 - 현재각, 도 단위) — null 이면
     *        피치 안내 없음. 양수 = 더 눕혀야 함, 음수 = 세워야 함.
     */
    @Synchronized
    fun processJudgment(
        state: GuidanceState,
        result: DeviationResult,
        nowMs: Long,
        zoomHandlesDistance: Boolean = false,
        readyBlockedReason: String? = null,
        pitchDeviationDeg: Float? = null,
    ): GuidanceDecision {
        val readiness = readinessEvaluator.evaluate(result, nowMs)
        // 존재 확인 진동 — 어떤 안내 분기든 상관없이 매 판정마다 갱신한다
        val presence = presenceActions(state.detected, nowMs)
        val actions = judge(
            state, result, readiness, nowMs, zoomHandlesDistance, readyBlockedReason,
            pitchDeviationDeg,
        )
        return GuidanceDecision(readiness, presence + actions)
    }

    /**
     * 지정 피사체가 [PRESENCE_VIBRATION_AFTER_MS] 이상 연속으로 화면에 잡혀 있으면 연속 진동을
     * 켜고, 화면에서 벗어나는 순간 끈다 — "지금 잡혀 있다"를 손으로 느끼는 채널.
     */
    private fun presenceActions(detected: Boolean, nowMs: Long): List<GuidanceAction> {
        // 지정된 대상이 없으면 존재 진동을 켜지 않는다 — "지정한 피사체" 전용 채널
        if (!subjectDesignated) {
            presenceSinceMs = null
            if (presenceVibrationOn) {
                presenceVibrationOn = false
                return listOf(GuidanceAction.PresenceVibrationStop)
            }
            return emptyList()
        }
        if (!detected) {
            presenceSinceMs = null
            if (presenceVibrationOn) {
                presenceVibrationOn = false
                return listOf(GuidanceAction.PresenceVibrationStop)
            }
            return emptyList()
        }
        val since = presenceSinceMs ?: nowMs.also { presenceSinceMs = it }
        if (!presenceVibrationOn && nowMs - since >= PRESENCE_VIBRATION_AFTER_MS) {
            presenceVibrationOn = true
            return listOf(GuidanceAction.PresenceVibrationStart)
        }
        return emptyList()
    }

    private fun judge(
        state: GuidanceState,
        result: DeviationResult,
        readiness: ReadinessVerdict,
        nowMs: Long,
        zoomHandlesDistance: Boolean,
        readyBlockedReason: String?,
        pitchDeviationDeg: Float? = null,
    ): List<GuidanceAction> {
        if (!state.detected) {
            // 첫 검출 전에는 "사라졌어요"(LOST)가 아니라 탐색 안내(t2/t3)를 쓴다 — 비프 없음
            return if (subjectEverDetected) onLost(nowMs) else onSearching(nowMs)
        }

        // 첫 검출 — 탐색 단계가 실제로 있었을 때만 "찾았어요"를 말한다 (스크립트 3-1).
        // 세션 시작부터 바로 보였다면 방향 안내가 곧장 시작되는 것으로 충분하다.
        if (!subjectEverDetected) {
            subjectEverDetected = true
            val searched = searchingSinceMs != null
            searchingSinceMs = null
            // "찾았어요"는 지정된 대상이 있을 때만 — 아무 물체나 잡힌 것을 찾았다고 하지 않는다
            if (searched && subjectDesignated) {
                lastSpokenAtMs = nowMs
                return listOf(GuidanceAction.Speak(subjectFoundUtterance()))
            }
        }

        // 재탐지 — 이탈 안내까지 나갔던 긴 LOST 에서 돌아오면 1회 알린다 (스크립트 4-10)
        val refound = lostSpoken
        // 다시 찾음 — LOST 에피소드 종료
        lostSinceMs = null
        lostSpoken = false
        if (refound && subjectDesignated) {
            lastSpokenAtMs = nowMs
            return listOf(GuidanceAction.Speak(REFIND_UTTERANCE))
        }

        // 음식 피치 (2026-08-25): 폰 각도가 목표를 벗어나 있으면 구도·READY 안내보다 먼저
        // 각도부터 맞춘다. 이 분기는 대상 검출 뒤에만 온다 — 발화가 무장(arm)하고 실제 검출이
        // 확정(confirm)하는 구조라, 아무것도 안 잡힌 허공에 "눕혀 주세요"를 말하지 않는다.
        if (pitchDeviationDeg != null && abs(pitchDeviationDeg) > PITCH_TOLERANCE_DEG) {
            readySpokenThisEpisode = false
            val direction = if (pitchDeviationDeg > 0) {
                GuidanceDirection.TILT_LAY
            } else {
                GuidanceDirection.TILT_RAISE
            }
            return speakDirectionIfDue(direction, nowMs)
        }

        if (readiness.ready) {
            if (readyBlockedReason != null) {
                return onReadyBlocked(readyBlockedReason, nowMs)
            }
            return onReady(nowMs)
        }
        // 추가 게이트(시선 등)는 구도 안정화 중에도 즉시 알려준다.
        if (readiness.candidateReady && readyBlockedReason != null) {
            return onReadyBlocked(readyBlockedReason, nowMs)
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
            return emptyList()
        }
        readySpokenThisEpisode = false
        val direction = pickDirection(state, result, zoomHandlesDistance)
        if (direction == null) {
            return heartbeat(readiness, zoomHandlesDistance, nowMs)
        }
        return speakDirectionIfDue(direction, nowMs)
    }

    /** 방향 단어 공통 게이팅 — 같은 방향은 [DIRECTION_REPEAT_MS], 바뀐 방향도 [DIRECTION_MIN_GAP_MS] 간격. */
    private fun speakDirectionIfDue(direction: GuidanceDirection, nowMs: Long): List<GuidanceAction> {
        heartbeatStateSinceMs = null
        val changed = direction != lastDirection
        val elapsed = nowMs - lastDirectionAtMs
        val due = if (changed) elapsed >= DIRECTION_MIN_GAP_MS else elapsed >= DIRECTION_REPEAT_MS
        if (!due) return emptyList()
        lastDirection = direction
        lastDirectionAtMs = nowMs
        lastSpokenAtMs = nowMs
        return listOf(GuidanceAction.Speak(direction.utterance), GuidanceAction.Vibrate)
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

    /**
     * 첫 검출 전의 탐색 단계 (노션 스크립트 3-2/3-3) — 경고음 없이 음성만.
     * [SEARCH_HINT_AFTER_MS] 뒤 "아직 안 보여요" 1회, [SEARCH_FAIL_AFTER_MS] 뒤 "못 찾았어요" 1회.
     */
    private fun onSearching(nowMs: Long): List<GuidanceAction> {
        readySpokenThisEpisode = false
        lastDirection = null
        heartbeatStateSinceMs = null
        // 지정 대상이 없으면 탐색 문장(안 보여요/못 찾았어요)도 내지 않는다
        if (!subjectDesignated) return emptyList()
        val since = searchingSinceMs ?: run { searchingSinceMs = nowMs; return emptyList() }
        if (!searchHintSpoken && nowMs - since >= SEARCH_HINT_AFTER_MS) {
            searchHintSpoken = true
            lastSpokenAtMs = nowMs
            return listOf(GuidanceAction.Speak(subjectSearchingUtterance()))
        }
        if (!searchFailSpoken && nowMs - since >= SEARCH_FAIL_AFTER_MS) {
            searchFailSpoken = true
            lastSpokenAtMs = nowMs
            return listOf(GuidanceAction.Speak(subjectNotFoundUtterance()))
        }
        return emptyList()
    }

    // 탐색 안내 문장 — SpeechCatalog 의 피사체 변형 음원과 완전 일치해야 한다 (조사 포함)
    private fun subjectFoundUtterance() = "$subjectWord${josa("을", "를")} 찾았어요."
    private fun subjectSearchingUtterance() =
        "$subjectWord${josa("이", "가")} 아직 안 보여요. 좌우로 천천히 움직여 주세요."
    private fun subjectNotFoundUtterance() =
        "$subjectWord${josa("을", "를")} 못 찾았어요. 더 찾을까요, 다른 대상을 고를까요?"
    private fun subjectLostUtterance() =
        "$subjectWord${josa("이", "가")} 화면에서 사라졌어요."

    /** 받침 유무로 조사 선택 — 한글이 아니면 받침 없는 쪽(를/가)을 쓴다. */
    private fun josa(withBatchim: String, withoutBatchim: String): String {
        val last = subjectWord.lastOrNull() ?: return withoutBatchim
        if (last !in '가'..'힣') return withoutBatchim
        return if ((last - '가') % 28 != 0) withBatchim else withoutBatchim
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
        if (!lostSpoken && subjectDesignated && nowMs - since >= LOST_SPEAK_AFTER_MS) {
            lostSpoken = true
            lastSpokenAtMs = nowMs
            // 커스텀 lostUtterance 를 넘긴 호출자(테스트)는 존중, 기본값이면 피사체 이름을 넣는다
            val text = if (lostUtterance == LOST_UTTERANCE) subjectLostUtterance() else lostUtterance
            actions.add(GuidanceAction.Speak(text))
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
        // 스크립트 5-1: READY 는 음성 + 확정 진동 1회를 함께 낸다
        return listOf(GuidanceAction.Speak(readyUtterance), GuidanceAction.Vibrate)
    }

    companion object {
        // 확정 문장 (5-1 READY / 이탈은 사용자 요청 2026-08-24 문구 / 4-10 재탐지) — 음원과 일치
        const val READY_UTTERANCE = "좋아요. 촬영할 수 있어요."
        const val LOST_UTTERANCE = "피사체가 화면에서 사라졌어요."
        const val REFIND_UTTERANCE = "다시 찾았어요."
        const val DEFAULT_SUBJECT = "피사체"

        /** 탐색(첫 검출 전) — "아직 안 보여요" / "못 찾았어요" 안내 시점 (스크립트 3-2/3-3). */
        const val SEARCH_HINT_AFTER_MS = 4_000L
        const val SEARCH_FAIL_AFTER_MS = 12_000L

        const val DIRECTION_REPEAT_MS = 2_500L
        const val DIRECTION_MIN_GAP_MS = 1_000L
        const val LOST_DEBOUNCE_MS = 800L
        const val LOST_TONE_INTERVAL_MS = 3_000L
        /** 이탈 2초 뒤 "사라졌어요" 발화 (사용자 요청 2026-08-24 — 기존 6초에서 단축). */
        const val LOST_SPEAK_AFTER_MS = 2_000L

        /** 피사체가 이만큼 연속으로 잡혀 있으면 존재 확인 연속 진동 시작. */
        const val PRESENCE_VIBRATION_AFTER_MS = 1_500L

        /** 음식 피치 목표각 허용 오차 — 이 안이면 각도 안내를 멈추고 구도 안내로 넘어간다. */
        const val PITCH_TOLERANCE_DEG = 12f
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
