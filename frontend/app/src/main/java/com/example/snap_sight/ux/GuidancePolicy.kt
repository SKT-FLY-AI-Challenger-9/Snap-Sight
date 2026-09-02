package com.example.snap_sight.ux

import com.example.snap_sight.camera.PhoneRoll
import com.example.snap_sight.cv.DeviationResult
import com.example.snap_sight.cv.CanonicalReadinessEvaluator
import com.example.snap_sight.cv.CompositionProfile
import com.example.snap_sight.cv.FrameEdge
import com.example.snap_sight.cv.ReadinessBlocker
import com.example.snap_sight.cv.ReadinessVerdict
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

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
    /**
     * 지정 피사체가 화면에 잡혀 있는 동안의 연속 진동 (사용자 요청 2026-08-24). [level] 은
     * 0(목표에서 멂 — 느린 펄스) .. [GuidancePolicy.PRESENCE_LEVELS]-1(목표 범위 안 — 빠른
     * 펄스)로, 가까워질수록 빨라진다(엔드유저 피드백 2026-08-30). 시작할 때와 단계가 바뀔
     * 때만 나온다.
     */
    data class PresenceVibrationLevel(val level: Int) : GuidanceAction
    object PresenceVibrationStop : GuidanceAction
    object Vibrate : GuidanceAction
    object WarningTone : GuidanceAction
}

// 노션 확정 스크립트(스크립트 - 준서) 문장 — SpeechCatalog 의 프리캐싱 음원과 1:1 로 일치해야 한다.
//
// 좌우/상하 위치 안내는 시계 방향(1시~12시)으로 통합했다 (사용자 요청 2026-08-27) — "손으로 카메라를
// 돌려 1시, 2시, 3시처럼 안내받고 싶다"는 요청 반영. 가로/세로 편차를 하나의 벡터로 합쳐 대각선도
// 표현한다(12시=위, 3시=오른쪽, 6시=아래, 9시=왼쪽). 시간마다 고정 문구가 아니라 [hour] 값이 달라지므로
// enum 이 아니라 데이터 클래스로 둔다 — [GuidancePolicy.speakDirectionIfDue] 의 동일성 비교는 유지된다.
// 시각은 숫자("3시")가 아니라 순우리말 수사로 적는다 — 숫자로 두면 TTS가 한자어(삼 시)로 읽어
// 어색하다. 동작도 "이동"이 아니라 "회전"이다 — 손으로 폰을 그 방향으로 돌리라는 뜻이라서
// (사용자 요청 2026-08-27).
/** 시각(1..12)의 순우리말 수사 — "삼 시"가 아니라 "세 시"로 읽히도록 숫자 대신 이 표기를 쓴다. */
private val CLOCK_HOUR_WORDS = mapOf(
    1 to "한", 2 to "두", 3 to "세", 4 to "네", 5 to "다섯", 6 to "여섯",
    7 to "일곱", 8 to "여덟", 9 to "아홉", 10 to "열", 11 to "열한", 12 to "열두",
)

internal sealed class GuidanceDirection(val utterance: String) {
    /** 위치(좌우/상하) 편차를 하나의 시계 방향으로 합친 안내 — [hour] 는 1..12. */
    data class Clock(val hour: Int) : GuidanceDirection("${CLOCK_HOUR_WORDS.getValue(hour)} 시 방향으로 조금 회전해 주세요.")

    object CLOSER : GuidanceDirection("조금 가까이 가 주세요.")
    /** 정의만 남김 — [GuidancePolicy.pickDirection] 은 FARTHER 를 고르지 않는다. */
    object FARTHER : GuidanceDirection("조금 뒤로 당겨 주세요.")

    // 음식 피치 (2026-08-25) — 음식 세션에서 폰 각도를 목표(45°)로 유도. CV 편차가 아니라
    // 기울기 센서에서 오므로 pickDirection 이 아닌 judge() 의 피치 분기가 고른다.
    object TILT_LAY : GuidanceDirection("폰을 조금 더 눕혀 주세요.")
    object TILT_RAISE : GuidanceDirection("폰을 조금 세워 주세요.")

    // 일반 세션 수직 안내의 기울기 문구 (2026-08-25) — 피사체가 위/아래로 벗어난 원인이
    // 폰 피치로 보이면 Clock(12)/Clock(6) 대신 이 문구를 쓴다 ([GuidancePolicy.refineVerticalWithPitch]).
    // TODO: 노션 스크립트 미확정 임시 문구 — 확정되면 SpeechCatalog 음원과 함께 갱신 (그 전까지 TTS 폴백).
    object TILT_TOP_TOWARD : GuidanceDirection("폰 윗부분을 몸 쪽으로 기울여 주세요.")
    object TILT_TOP_AWAY : GuidanceDirection("폰 윗부분을 바깥쪽으로 기울여 주세요.")

    // 좌우 수평 (2026-08-30, 엔드유저 피드백 "폰이 좌우로 기울어진 경우") — 풍경 모드
    // [LandscapeGuide] 와 같은 문구·부호 규약([com.example.snap_sight.camera.PhoneRoll], 실기기
    // 확정 2026-08-28: 폰을 왼쪽(반시계)으로 돌리면 roll 이 +). +편차 = 왼쪽으로 지나침 →
    // 오른쪽으로 되돌리기.
    // TODO: 노션 스크립트 미확정 임시 문구 — 확정되면 SpeechCatalog 음원과 함께 갱신 (그 전까지 TTS 폴백).
    object ROLL_TURN_LEFT : GuidanceDirection("폰이 기울었어요. 왼쪽으로 조금 돌려 주세요.")
    object ROLL_TURN_RIGHT : GuidanceDirection("폰이 기울었어요. 오른쪽으로 조금 돌려 주세요.")
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
    /** 마지막으로 내보낸 존재 진동 단계 — 꺼져 있으면 -1. */
    private var presenceLevel = -1

    // ---- 좌우 수평 안내 (2026-08-30) — 진입 [PhoneRoll.ENTER_DEG]/해제 [PhoneRoll.EXIT_DEG] 히스테리시스 ----
    private var rollActive = false
    /** 기울기가 임계값을 넘기 시작한 시각 — [ROLL_DEBOUNCE_MS] 이상 이어져야 말한다. */
    private var rollOverSinceMs: Long? = null

    // ---- LOST 중 "마지막으로 보였던 방향" 추적 (사용자 요청 2026-08-27) ----
    // 화면 안에 보이는 동안은 카메라 반화각(~33도) 안에서만 방향이 나오지만(11시~1시 근처),
    // 완전히 놓치면(LOST) 마지막으로 본 방향 + 그 이후 자이로로 잰 회전량을 반영해 2시·10시까지도
    // 안내한다 — 말하는 시각은 항상 10~2시(10·11·1·2)로만 묶는다(사용자 요청 2026-08-28).
    private var lastKnownRightRad: Float? = null
    private var lastKnownOrientationRad: Pair<Float, Float>? = null
    private var lastSearchDirectionAtMs: Long = Long.MIN_VALUE / 2

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
        presenceLevel = -1
        rollActive = false
        rollOverSinceMs = null
        lastKnownRightRad = null
        lastKnownOrientationRad = null
        lastSearchDirectionAtMs = Long.MIN_VALUE / 2
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
        phonePitchDeg: Float? = null,
        cameraOrientationRad: Pair<Float, Float>? = null,
        personSession: Boolean = false,
        phoneRollDeg: Float? = null,
        personFramingBusy: Boolean = false,
    ): List<GuidanceAction> = processJudgment(
        state = state,
        result = result,
        nowMs = nowMs,
        zoomHandlesDistance = zoomHandlesDistance,
        readyBlockedReason = readyBlockedReason,
        pitchDeviationDeg = pitchDeviationDeg,
        phonePitchDeg = phonePitchDeg,
        cameraOrientationRad = cameraOrientationRad,
        personSession = personSession,
        phoneRollDeg = phoneRollDeg,
        personFramingBusy = personFramingBusy,
    ).actions

    /**
     * 액션과 UI가 같은 evaluator 호출의 verdict를 공유하도록 하는 canonical 진입점.
     *
     * @param pitchDeviationDeg 음식 세션의 폰 각도 편차 (목표각 - 현재각, 도 단위) — null 이면
     *        피치 안내 없음. 양수 = 더 눕혀야 함, 음수 = 세워야 함.
     * @param phonePitchDeg 현재 폰 피치 (TiltSensorMonitor 규약: 양수 = 카메라가 아래를 봄) —
     *        일반 세션의 수직 이동 안내를 기울기 문구로 바꾸는 데만 쓴다. null 이면 항상 이동 문구.
     * @param cameraOrientationRad AIMING 시작 이후 누적 카메라 회전량(라디안, yaw to pitch) —
     *        null이 아니면 시계 방향 안내를 "화면 속 위치"가 아니라 "카메라 켜진 순간(12시)
     *        기준으로 실제 얼마나 돌았는지 + 남은 보정"으로 계산한다 (사용자 요청 2026-08-27).
     * @param phoneRollDeg 현재 폰 좌우 기울기(도, TiltSensorMonitor.rollDegrees 규약 — [PhoneRoll]
     *        참고). 가장 가까운 파지 스냅(0/±90/180°)에서 [PhoneRoll.ENTER_DEG] 이상 벗어난 채
     *        [ROLL_DEBOUNCE_MS] 이어지면 다른 축보다 먼저 수평 문구를 말하고, [PhoneRoll.EXIT_DEG]
     *        안으로 돌아오면 [LEVEL_UTTERANCE] 1회 (2026-08-30). null 이면 수평 안내 없음.
     */
    @Synchronized
    fun processJudgment(
        state: GuidanceState,
        result: DeviationResult,
        nowMs: Long,
        zoomHandlesDistance: Boolean = false,
        readyBlockedReason: String? = null,
        pitchDeviationDeg: Float? = null,
        phonePitchDeg: Float? = null,
        cameraOrientationRad: Pair<Float, Float>? = null,
        personSession: Boolean = false,
        phoneRollDeg: Float? = null,
        personFramingBusy: Boolean = false,
    ): GuidanceDecision {
        val readiness = readinessEvaluator.evaluate(result, nowMs)
        // 존재 확인 진동 — 어떤 안내 분기든 상관없이 매 판정마다 갱신한다
        val presence = presenceActions(state, result, nowMs)
        val actions = judge(
            state, result, readiness, nowMs, zoomHandlesDistance, readyBlockedReason,
            pitchDeviationDeg, phonePitchDeg, cameraOrientationRad, personSession, phoneRollDeg,
            personFramingBusy,
        )
        return GuidanceDecision(readiness, presence + actions)
    }

    private sealed interface RollGuidance {
        /** 교정 문구를 말해야 한다 (방향 안내 게이팅을 탄다). */
        data class Correct(val direction: GuidanceDirection) : RollGuidance
        /** 방금 수평으로 돌아왔다 — 복귀 확인 1회. */
        object Leveled : RollGuidance
    }

    /**
     * 좌우 수평 안내 (2026-08-30) — 풍경 모드 [LandscapeGuide.onRoll] 과 같은 규약. 가장 가까운
     * 파지 스냅으로부터의 편차([PhoneRoll.deviationFromNearestSnap])가 [PhoneRoll.ENTER_DEG] 이상으로
     * [ROLL_DEBOUNCE_MS] 이어지면 시작하고, 한 번 시작하면 [PhoneRoll.EXIT_DEG] 안으로 돌아올 때까지
     * 유지한다(히스테리시스 — 임계값 하나로는 경계에서 "돌려 주세요/침묵"이 반복된다). 돌아오면
     * [RollGuidance.Leveled] 를 1회 돌려준다. 디바운스는 풍경에는 없는 추가 조건 — 피사체를
     * 조준하는 세션은 손이 더 많이 움직여 순간 기울기가 잦다.
     */
    private fun rollGuidance(rollDeg: Float?, nowMs: Long): RollGuidance? {
        if (rollDeg == null || !rollDeg.isFinite()) {
            rollActive = false
            rollOverSinceMs = null
            return null
        }
        val deviation = PhoneRoll.deviationFromNearestSnap(rollDeg)
        val magnitude = abs(deviation)
        if (rollActive && magnitude <= PhoneRoll.EXIT_DEG) {
            rollActive = false
            rollOverSinceMs = null
            return RollGuidance.Leveled
        }
        if (!rollActive) {
            if (magnitude < PhoneRoll.ENTER_DEG) {
                rollOverSinceMs = null
                return null
            }
            val since = rollOverSinceMs ?: nowMs.also { rollOverSinceMs = it }
            if (nowMs - since < ROLL_DEBOUNCE_MS) return null
            rollActive = true
        }
        // +편차 = 왼쪽(반시계)으로 지나침 → 오른쪽으로 되돌리기 (실기기 확정 2026-08-28)
        val direction = if (deviation > 0f) GuidanceDirection.ROLL_TURN_RIGHT else GuidanceDirection.ROLL_TURN_LEFT
        return RollGuidance.Correct(direction)
    }

    /**
     * 지정 피사체가 [PRESENCE_VIBRATION_AFTER_MS] 이상 연속으로 화면에 잡혀 있으면 연속 진동을
     * 켜고, 화면에서 벗어나는 순간 끈다 — "지금 잡혀 있다"를 손으로 느끼는 채널.
     *
     * 펄스 빠르기는 목표 범위까지의 거리([presenceDeviationScore])로 정한다 — 멀수록 느리고
     * 가까워질수록 빨라진다(엔드유저 피드백 2026-08-30, "목표에서 얼마나 벗어났는지를 진동으로").
     * 단계는 시작할 때와 바뀔 때만 액션으로 내보내고, 경계 근처의 손떨림으로 단계가
     * 왔다갔다하지 않게 히스테리시스([PRESENCE_LEVEL_HYSTERESIS])를 둔다.
     */
    private fun presenceActions(
        state: GuidanceState,
        result: DeviationResult,
        nowMs: Long,
    ): List<GuidanceAction> {
        // 지정된 대상이 없으면 존재 진동을 켜지 않는다 — "지정한 피사체" 전용 채널
        if (!subjectDesignated || !state.detected) {
            presenceSinceMs = null
            if (presenceVibrationOn) {
                presenceVibrationOn = false
                presenceLevel = -1
                return listOf(GuidanceAction.PresenceVibrationStop)
            }
            return emptyList()
        }
        val since = presenceSinceMs ?: nowMs.also { presenceSinceMs = it }
        if (!presenceVibrationOn) {
            if (nowMs - since < PRESENCE_VIBRATION_AFTER_MS) return emptyList()
            presenceVibrationOn = true
        }
        val level = presenceLevelFor(presenceDeviationScore(result), presenceLevel)
        if (level == presenceLevel) return emptyList()
        presenceLevel = level
        return listOf(GuidanceAction.PresenceVibrationLevel(level))
    }

    private fun judge(
        state: GuidanceState,
        result: DeviationResult,
        readiness: ReadinessVerdict,
        nowMs: Long,
        zoomHandlesDistance: Boolean,
        readyBlockedReason: String?,
        pitchDeviationDeg: Float? = null,
        phonePitchDeg: Float? = null,
        cameraOrientationRad: Pair<Float, Float>? = null,
        personSession: Boolean = false,
        phoneRollDeg: Float? = null,
        personFramingBusy: Boolean = false,
    ): List<GuidanceAction> {
        if (!state.detected) {
            // 첫 검출 전에는 "사라졌어요"(LOST)가 아니라 탐색 안내(t2/t3)를 쓴다 — 비프 없음
            return if (subjectEverDetected) onLost(nowMs, cameraOrientationRad) else onSearching(nowMs)
        }

        // 보이는 동안 "마지막으로 어느 쪽으로 벗어나 있었는지"를 90도(=9시/3시)로 기록해 둔다
        // — 완전히 놓치는(LOST) 순간 그 방향에서부터 시작해, 그 이후 실제로 돈 만큼(자이로)을
        // 반영해 안내를 갱신한다(사용자 요청 2026-08-27). 위아래는 섞지 않는다 — 시계는
        // 좌우 전용이다("8시·5시·7시 이런 게 나오면 안 돼").
        if (cameraOrientationRad != null) {
            val hSign = when (state.horizontal) {
                HorizontalAlignment.LEFT -> -1f
                HorizontalAlignment.RIGHT -> 1f
                else -> 0f
            }
            if (hSign != 0f) {
                lastKnownRightRad = hSign * (Math.PI.toFloat() / 2f)
                lastKnownOrientationRad = cameraOrientationRad
            }
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

        // 좌우 수평 (2026-08-30): 다른 축과 독립이고 중력 기준이라 눈 없이도 손목으로 1초면
        // 고쳐지므로 위치·피치보다 먼저 말한다 — 기울어진 채로 위치를 맞춰봐야 사진이 기울어져
        // 있다. 피치 분기처럼 대상 검출 뒤에만 온다.
        when (val roll = rollGuidance(phoneRollDeg, nowMs)) {
            is RollGuidance.Correct -> {
                readySpokenThisEpisode = false
                return speakDirectionIfDue(roll.direction, nowMs)
            }
            RollGuidance.Leveled -> {
                // 풍경 모드와 같은 복귀 확인 1회. 다음 방향 안내는 최소 간격 뒤에 이어진다.
                lastDirection = null
                lastDirectionAtMs = nowMs
                lastSpokenAtMs = nowMs
                return listOf(GuidanceAction.Speak(LEVEL_UTTERANCE))
            }
            null -> Unit
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
            // 인물 프레이밍이 줌·도달·촬영 확인을 맡는 동안은 "좋아요"를 내지 않는다 (실기기
            // 2026-08-31 — 줌 중에 사람이 가운데면 일반 READY 가 떠서 "좋아요"가 겹쳐 나왔다).
            // 도달 알림은 프레이밍 흐름(진동·"이대로 찍을까요?")이 대신한다.
            if (personFramingBusy) return emptyList()
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
            ?: visibilityFallbackDirection(readiness, result, personSession)
        if (direction == null) {
            // 프레이밍 국면 문구("구도에 맞게 확대하는 중이에요")가 화면에 있으니 하트비트도 쉼
            if (personFramingBusy) return emptyList()
            return heartbeat(readiness, zoomHandlesDistance, nowMs)
        }
        val refined = refineVerticalWithPitch(direction, phonePitchDeg)
        // 인물 프레이밍이 줌으로 상하·거리를 바꾸는 동안 "가까이/윗부분을 기울여" 류는 판정과
        // 충돌한다 (2026-08-31) — 좌우 시계 안내만 계속 낸다 (중앙 유도는 여전히 정책 몫).
        if (personFramingBusy && !allowedDuringPersonFraming(refined)) return emptyList()
        return speakDirectionIfDue(refined, nowMs)
    }

    /**
     * 위치는 중앙인데(pickDirection == null) bbox 가장자리가 잘려 있으면(VISIBILITY) 어느
     * 변이 가장 심하게 잘렸는지로 기존 시계·기울기 문구 중 하나를 고른다 — "피사체 전체가
     * 화면 안에 들어오게" 같은 별도 문구 대신 항상 쓰던 어휘를 재사용한다(사용자 요청
     * 2026-08-28). 인물 세션은 [PersonFramingController]가 줌·진동·자동촬영으로 대신
     * 처리하므로 제외한다(사용자 요청 2026-08-28 — "너무 가깝다는 멘트가 뜨던데 줌할 때").
     */
    private fun visibilityFallbackDirection(
        readiness: ReadinessVerdict,
        result: DeviationResult,
        personSession: Boolean,
    ): GuidanceDirection? {
        if (personSession || ReadinessBlocker.VISIBILITY !in readiness.blockers) return null
        val visibility = result.frameVisibility ?: return null
        val margins = listOf(
            FrameEdge.LEFT to visibility.leftMargin,
            FrameEdge.TOP to visibility.topMargin,
            FrameEdge.RIGHT to visibility.rightMargin,
            FrameEdge.BOTTOM to visibility.bottomMargin,
        )
        return when (margins.minByOrNull { it.second }?.first) {
            FrameEdge.LEFT -> GuidanceDirection.Clock(11)
            FrameEdge.RIGHT -> GuidanceDirection.Clock(1)
            FrameEdge.TOP -> GuidanceDirection.TILT_TOP_TOWARD
            FrameEdge.BOTTOM -> GuidanceDirection.TILT_TOP_AWAY
            null -> null
        }
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
            // 인물 세션의 VISIBILITY(너무 가까움)는 이제 여기서 아무 말도 하지 않는다 —
            // MainActivity의 [PersonFramingController]가 줌·진동·3초 확정 촬영으로 전부
            // 대신하므로 "대상이 너무 가까워요" 안내가 그 진행 중에 겹쳐 나오면 오히려
            // 혼란스럽다(사용자 요청 2026-08-28 — "너무 가깝다는 멘트가 뜨던데 줌할 때").
            // 인물이 아닌 세션의 VISIBILITY 도 이제 여기까지 오지 않는다 — judge()의
            // visibilityFallbackDirection 이 먼저 시계·기울기 문구로 처리한다(사용자 요청
            // 2026-08-28: "피사체 전체가 화면 안에 들어오게" 문구를 없애자).
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
            return listOf(GuidanceAction.Speak(SUBJECT_NOT_FOUND_UTTERANCE))
        }
        return emptyList()
    }

    // 탐색 안내 문장 — SpeechCatalog 의 피사체 변형 음원과 완전 일치해야 한다 (조사 포함)
    private fun subjectFoundUtterance() = "$subjectWord${josa("을", "를")} 찾았어요."
    private fun subjectSearchingUtterance() =
        "$subjectWord${josa("이", "가")} 아직 안 보여요. 좌우로 천천히 움직여 주세요."
    private fun subjectLostUtterance() =
        "$subjectWord${josa("이", "가")} 화면에서 사라졌어요."

    /** 받침 유무로 조사 선택 — 한글이 아니면 받침 없는 쪽(를/가)을 쓴다. */
    private fun josa(withBatchim: String, withoutBatchim: String): String {
        val last = subjectWord.lastOrNull() ?: return withoutBatchim
        if (last !in '가'..'힣') return withoutBatchim
        return if ((last - '가') % 28 != 0) withBatchim else withoutBatchim
    }

    private fun onLost(nowMs: Long, cameraOrientationRad: Pair<Float, Float>?): List<GuidanceAction> {
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
        } else {
            // "사라졌어요"를 말하는 틱이 아니면, 마지막으로 보였던 방향 + 그 이후 회전량으로
            // 계속 검색 방향을 알려준다 — 여기서만 2시·10시까지 갈 수 있다(사용자 요청 2026-08-27,
            // 2026-08-28에 3·9시도 음성에서 뺐다).
            searchDirectionHour(nowMs, cameraOrientationRad)?.let { hour ->
                lastSpokenAtMs = nowMs
                actions.add(GuidanceAction.Speak(GuidanceDirection.Clock(hour).utterance))
            }
        }
        return actions
    }

    /**
     * 화면에서 완전히 벗어난 뒤 마지막으로 보였던 좌우 방향([lastKnownRightRad], 9시/3시 기준)에
     * 그 순간부터 지금까지 실제로 돈 만큼(자이로 누적량 차이)을 반영해 지금 돌아야 할 방향을
     * 시계로 계산한다. 위아래는 섞지 않는다 — 좌우 회전량만 본다("8시·5시·7시 이런 게 나오면
     * 안 돼", 사용자 요청 2026-08-27). [DIRECTION_REPEAT_MS] 간격으로만 반복한다. 필요한 값이
     * 하나라도 없으면(자이로 없음/아직 한 번도 좌우로 안 벗어나 봄) null.
     */
    private fun searchDirectionHour(nowMs: Long, cameraOrientationRad: Pair<Float, Float>?): Int? {
        val lastRight = lastKnownRightRad ?: return null
        val (lastYawRad, _) = lastKnownOrientationRad ?: return null
        val (currentYawRad, _) = cameraOrientationRad ?: return null
        if (nowMs - lastSearchDirectionAtMs < DIRECTION_REPEAT_MS) return null
        lastSearchDirectionAtMs = nowMs
        // accum 규약: yaw는 오른쪽으로 돌수록 음수 → "그 이후 오른쪽으로 돈 양"은 (last - current).
        val turnedRightSinceRad = lastYawRad - currentYawRad
        val nowRight = lastRight - turnedRightSinceRad
        if (nowRight == 0f) return null
        val hour = wrapHour((Math.toDegrees(nowRight.toDouble()) / 30.0).roundToInt())
        // 말하는 시각은 10~2시(10·11·1·2, 12시는 기울기 문구 몫)로만 한다(사용자 요청
        // 2026-08-28: "10시부터 2시까지 빼고는 아예 음성도 삭제해버려"). 그 밖(3~9시)으로
        // 많이 지나쳤으면 처음 놓쳤던 쪽(2시 또는 10시)의 가장 먼 경계에 고정한다.
        return clampToSpokenHour(hour, cameFromRight = lastRight > 0f)
    }

    private fun clampToSpokenHour(hour: Int, cameFromRight: Boolean): Int = when (hour) {
        in 3..9 -> if (cameFromRight) 2 else 10
        12 -> if (cameFromRight) 1 else 11
        else -> hour
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

        /**
         * 탐색 실패([SEARCH_FAIL_AFTER_MS] 이상 한 번도 못 찾음) 안내 (사용자 요청 2026-08-28 —
         * "그냥 '피사체가 화면에서 없습니다' 라고 하지 말고"). 대상 이름을 넣지 않는 고정 문구라
         * 대상별 SpeechCatalog 변형이 필요 없다 — 손을 어느 쪽으로 돌려야 할지까지 안내한다.
         */
        const val SUBJECT_NOT_FOUND_UTTERANCE =
            "말씀하신 대상을 찾지 못했어요. 핸드폰을 아홉 시부터 세 시 방향으로 돌려 주세요."

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

        // ---- 존재 진동 단계 (2026-08-30) — 목표 범위에 가까워질수록 펄스가 빨라진다 ----

        /** 존재 진동 펄스 단계 수 — 0(목표에서 멂·느림) .. PRESENCE_LEVELS-1(목표 범위 안·빠름). */
        const val PRESENCE_LEVELS = 4

        /**
         * 단계별 편차 점수 상한(미만). 점수 = max(|x|/x 허용치, |y|/y 허용치) — 1.0 미만이면
         * 위치가 목표 범위 안이다. 인덱스가 단계: [3]=1.0(범위 안), [2]=1.5, [1]=2.0, [0]=무한.
         * x 허용치 0.20 기준으로 |x| < 0.2 / 0.3 / 0.4 / 그 이상에 해당한다.
         */
        private val PRESENCE_LEVEL_UPPER_BOUNDS =
            floatArrayOf(Float.POSITIVE_INFINITY, 2.0f, 1.5f, 1.0f)

        /** 단계 경계 히스테리시스(점수 단위) — 경계 위에서 손떨림으로 단계가 튀지 않게. */
        const val PRESENCE_LEVEL_HYSTERESIS = 0.15f

        /**
         * 목표 범위까지의 거리 점수 — 좌우·상하 중 더 벗어난 축의 (편차 / 허용치). 크기(거리)
         * 편차는 넣지 않는다 — bbox 면적 기반 거리 판단은 부정확해 음성 안내에서도 제외돼 있다.
         */
        fun presenceDeviationScore(result: DeviationResult): Float {
            val goal = result.goal ?: CompositionProfile.DEFAULT.goalFor(result.framing)
            val x = abs(result.xDeviation ?: 0f) / goal.maxAbsXDeviation.coerceAtLeast(1e-6f)
            val y = abs(result.yDeviation ?: 0f) / goal.maxAbsYDeviation.coerceAtLeast(1e-6f)
            return max(x, y)
        }

        /**
         * 점수 → 단계. [currentLevel] 이 유효하면(0..PRESENCE_LEVELS-1) 경계에
         * [PRESENCE_LEVEL_HYSTERESIS] 만큼 여유를 두고서만 옮긴다 — 올라가려면 상한보다 그만큼
         * 더 안쪽, 내려가려면 그만큼 더 바깥쪽이어야 한다. 유효하지 않으면(진동 시작) 여유 없이
         * 바로 해당 단계를 고른다.
         */
        fun presenceLevelFor(score: Float, currentLevel: Int): Int {
            val top = PRESENCE_LEVELS - 1
            if (currentLevel !in 0..top) {
                return (top downTo 0).first { score < PRESENCE_LEVEL_UPPER_BOUNDS[it] }
            }
            var level = currentLevel
            while (level < top && score < PRESENCE_LEVEL_UPPER_BOUNDS[level + 1] - PRESENCE_LEVEL_HYSTERESIS) level++
            while (level > 0 && score >= PRESENCE_LEVEL_UPPER_BOUNDS[level] + PRESENCE_LEVEL_HYSTERESIS) level--
            return level
        }

        // 좌우는 3칸이 아니라 프레임을 가로로 5등분(각 1/5)한 열로 판정한다(사용자 요청
        // 2026-08-28: "3*3이지만 중심을 못 맞추는 거 같아서 3*5로 더 나누고"). 왼쪽부터
        // 1열=10시, 2열=11시, 3열=정중앙(제자리 → 다른 축도 맞으면 촬영 안내), 4열=1시,
        // 5열=2시. 아래 두 경계가 1열|2열, 4열|5열을 가른다 — 2열|3열, 3열|4열 경계는
        // [GuidanceStateMapper]의 좌우 정렬(CENTER) 임계값이 대신 가른다. 물체 크기와
        // 무관하게 판정하려고 여백(margin) 대신 무게중심 위치를 쓴다(사용자 요청 2026-08-27).
        // 행(상하) 기준은 그대로 [verticalDirection] 이 담당한다 — 이번 변경은 열(좌우)만.
        private const val ZONE_OUTER_HALF_LEFT = 1f / 5f
        private const val ZONE_OUTER_HALF_RIGHT = 4f / 5f

        /** 음식 피치 목표각 허용 오차 — 이 안이면 각도 안내를 멈추고 구도 안내로 넘어간다. */
        const val PITCH_TOLERANCE_DEG = 12f

        // 좌우 수평 안내 (2026-08-30). 임계값·스냅 규약은 [PhoneRoll](풍경 모드와 공용). 안내 임계값
        // 아래의 작은 기울기는 저장 시 수평 보정(HorizonStraightener, ≤12°)이 조용히 고친다.
        /** 수평 복귀 확인 — 풍경 모드([LandscapeGuide])와 같은 문구. */
        const val LEVEL_UTTERANCE = "수평이 맞았어요."
        /** 기울기가 진입 임계값을 이만큼 연속으로 넘어야 말한다 — 순간적인 손 움직임은 무시. */
        const val ROLL_DEBOUNCE_MS = 1_000L

        /**
         * 일반 세션에서 수직 이동 안내를 기울기 문구로 바꾸는 최소 폰 피치 (2026-08-25).
         * 이보다 덜 기울었으면 위치 문제로 보고 기존 "위로/아래로"를 유지한다.
         */
        const val PITCH_WORDING_SWAP_DEG = 15f
        const val READY_DEBOUNCE_MS = 300L
        const val READY_RESPEAK_MS = 3_000L

        /** 어떤 음성도 없는 상태가 이만큼 이어지면 하트비트 상태 안내를 1회 말한다 (반복도 이 간격). */
        const val HEARTBEAT_AFTER_MS = 4_000L
        const val AUTO_ZOOM_HEARTBEAT = "구도를 자동으로 맞추는 중이에요"
        const val HOLD_STEADY_HEARTBEAT = "좋아요, 그대로 유지해주세요"

        /**
         * 수직 이동 안내를 기울기 문구로 바꿀지 결정 (2026-08-25).
         *
         * 피사체가 프레임 위(UP)인데 폰이 이미 [PITCH_WORDING_SWAP_DEG] 이상 앞으로 기울어
         * 카메라가 아래를 보고 있으면(양수 피치), 원인은 위치가 아니라 기울기다 — "위로 이동"
         * 대신 "윗부분을 몸 쪽으로 기울이기"가 더 실행하기 쉬운 지시다(손목 > 팔 전체).
         * 반대(DOWN + 음수 피치)도 동일. 교정이 수평(0°)에서 **멀어지는** 조합(피사체가 정말
         * 높은 선반 위에 있는 경우 등)은 바꾸지 않는다 — 그땐 이동 안내가 맞다.
         * 발화 슬롯은 하나뿐이라 이동 문구와 기울기 문구가 동시에 나가는 일은 없다.
         */
        fun refineVerticalWithPitch(
            direction: GuidanceDirection,
            phonePitchDeg: Float?,
        ): GuidanceDirection {
            if (phonePitchDeg == null || direction !is GuidanceDirection.Clock) return direction
            return when {
                direction.hour == 12 && phonePitchDeg > PITCH_WORDING_SWAP_DEG ->
                    GuidanceDirection.TILT_TOP_TOWARD
                direction.hour == 6 && phonePitchDeg < -PITCH_WORDING_SWAP_DEG ->
                    GuidanceDirection.TILT_TOP_AWAY
                else -> direction
            }
        }

        /**
         * 좌우·상하 중 더 급한 쪽 하나만 고르고, 좌우면 시계로, 상하면 12시/6시로 말한다 —
         * 절대 섞지 않는다("8시·5시·7시 이런 게 나오면 안 돼", 사용자 요청 2026-08-27). 12시
         * =정면(카메라가 지금 향한 곳). 좌우는 프레임 가장자리에 얼마나 가까운지로 3단계 구역:
         * 안 걸림(11/1시), 가장자리에 거의 걸림(10/2시) — 화면 안에서 나올 수 있는 가장 먼
         * 시각이다. 완전히 벗어나면(LOST) 그 이후로도 10/11/1/2시 안에서만 자이로로 계속
         * 갱신하는 건 [searchDirectionHour]가 담당한다(2026-08-28: 3·9시도 음성에서 뺐다).
         */
        fun pickDirection(
            state: GuidanceState,
            result: DeviationResult,
            zoomHandlesDistance: Boolean = false,
        ): GuidanceDirection? {
            if (!state.detected) return null
            val x = result.xDeviation ?: 0f
            val y = result.yDeviation ?: 0f
            val size = result.sizeDeviation ?: 0f
            val goal = result.goal ?: CompositionProfile.DEFAULT.goalFor(result.framing)

            val hSign = when (state.horizontal) {
                HorizontalAlignment.LEFT -> -1f
                HorizontalAlignment.RIGHT -> 1f
                else -> 0f
            }
            val vSign = when (state.vertical) {
                VerticalAlignment.UP -> -1f
                VerticalAlignment.DOWN -> 1f
                else -> 0f
            }
            val hScore = if (hSign != 0f) abs(x) / goal.maxAbsXDeviation.coerceAtLeast(1e-6f) else 0f
            val vScore = if (vSign != 0f) abs(y) / goal.maxAbsYDeviation.coerceAtLeast(1e-6f) else 0f
            val clockScore = max(hScore, vScore)

            var best: GuidanceDirection? = null
            var bestScore = 0f
            if (clockScore > bestScore) {
                val direction = when {
                    hSign != 0f && hScore >= vScore ->
                        GuidanceDirection.Clock(horizontalZoneHour(hSign, x + goal.anchorX))
                    vSign != 0f -> verticalDirection(vSign)
                    else -> null
                }
                if (direction != null) {
                    best = direction
                    bestScore = clockScore
                }
            }
            if (state.distance == DistanceAlignment.CLOSER && !zoomHandlesDistance) {
                val closerScore = abs(size) / goal.maxAbsAreaDeviation.coerceAtLeast(1e-6f)
                if (closerScore > bestScore) {
                    best = GuidanceDirection.CLOSER
                    bestScore = closerScore
                }
            }
            // FARTHER는 안내하지 않는다 (KDoc 참고)
            return best
        }

        /**
         * 화면에 보이는 동안의 좌우 구역 — 프레임을 가로로 5등분한 열 중 어디에 무게중심이
         * 있는지로 정한다(사용자 요청 2026-08-28, 3x3 → 3x5). 왼쪽 끝 열(1열, centerX <
         * [ZONE_OUTER_HALF_LEFT])이면 10시, 그다음 열(2열)이면 11시 — 오른쪽도 대칭으로
         * 4열이면 1시, 맨 끝 열(5열, centerX > [ZONE_OUTER_HALF_RIGHT])이면 2시. 프레임
         * 가장자리 여백(margin) 대신 무게중심 위치를 쓰는 이유 — 물체 크기가 다르면 같은
         * 위치라도 여백 비율이 달라져 판정이 들쭉날쭉했다(사용자 요청 2026-08-27).
         */
        private fun horizontalZoneHour(hSign: Float, centerX: Float): Int = if (hSign > 0f) {
            if (centerX > ZONE_OUTER_HALF_RIGHT) 2 else 1
        } else {
            if (centerX < ZONE_OUTER_HALF_LEFT) 10 else 11
        }

        /**
         * 수직 방향 — 12시·6시는 음성 목록에서 뺐다(사용자 요청 2026-08-27: "3,2,1,11,10,9만
         * 있어야 해"). 그래서 상하 편차는 완만/급함 구분 없이 항상 "몸쪽으로/바깥쪽으로
         * 기울여 주세요"로 말한다 — 시계는 좌우 전용.
         */
        private fun verticalDirection(vSign: Float): GuidanceDirection =
            if (vSign < 0f) GuidanceDirection.TILT_TOP_TOWARD else GuidanceDirection.TILT_TOP_AWAY

        /**
         * 인물 프레이밍이 줌·상하를 맡는 동안에도 말해도 되는 안내인가 — 좌우 시계(10/11/1/2시)와
         * 수평 회전만. 나머지(가까이·기울이기)는 줌이 만드는 변화와 충돌한다 (2026-08-31).
         */
        private fun allowedDuringPersonFraming(direction: GuidanceDirection): Boolean =
            direction is GuidanceDirection.Clock ||
                direction === GuidanceDirection.ROLL_TURN_LEFT ||
                direction === GuidanceDirection.ROLL_TURN_RIGHT

        /** 1~12 범위로 감는다 (0 또는 음수 → +12, 13 이상 → 12를 뺌을 반복). */
        private fun wrapHour(rawHour: Int): Int {
            var hour = rawHour % 12
            if (hour <= 0) hour += 12
            return hour
        }
    }
}
