package com.example.snap_sight.ux

/**
 * 인물 프레이밍 (2026-08-28) — 인물 세션에서 바운딩 박스가 화면에 다 들어오는지로 분기한다
 * (사용자 요청: "인물이라고 판정됐을 때 바운딩 박스가 화면에 다 안에 안 들어오면 분기").
 * 좌우는 기존 [GuidancePolicy] 3x5 시계 안내가 그대로 맡고, 이 클래스는 상하/줌 축만 본다.
 *
 *  1. bbox 가 화면을 벗어남([CanonicalReadinessEvaluator]의 VISIBILITY 블로커) — 너무 가까워
 *     전신이 안 들어오는 경우. 줌은 아예 걸지 않는다. 머리(코) 좌표가 [CASE1_HEAD_MIN]..
 *     [CASE1_HEAD_MAX] 사이면(위=0,아래=1) 목표 도달로 보고 진동 1회 — bbox 가 화면을
 *     가득 채워도 상관없다(사용자 요청 2026-08-28: "바운딩 박스는 신경쓰지 말라").
 *  2. bbox 가 화면 안에 다 들어옴 — 10%씩 점차 줌인([RequestZoomStep], 실제 반영 여부는
 *     [AutoZoomController] 쿨다운이 결정)하면서 머리·발(양 발목 평균) 좌표가
 *     [CASE2_HEAD_MIN]..[CASE2_HEAD_MAX] / [CASE2_FOOT_MIN]..[CASE2_FOOT_MAX] 에 들어오면
 *     목표 도달. 진동은 줌인을 "시작할 때"(첫 스텝) 1회, "끝났을 때"(목표 도달) 1회 — 두
 *     경계에서만.
 *
 * 두 경우 모두 목표 도달 후 [HOLD_MS](3초) 동안 유지되면 [Capture] 를 1회만 반환한다 —
 * READY 유지를 요구하는 기존 [AutoCaptureArbiter] 와 달리 인물 프레이밍 전용의 짧은 확정
 * 창이다. [GuidancePolicy] 처럼 android.* 의존이 없어 시각(nowMs)을 주입받아 테스트한다.
 *
 * bbox 높이가 [CLOSE_ENOUGH_HEIGHT_RATIO] 이상으로 [CLOSE_ENOUGH_HOLD_MS] 이상 유지되면
 * 머리/발 좌표와 무관하게 곧장 목표 도달로 본다(사용자 요청 2026-08-28) — 극근접에서는 포즈
 * 랜드마크가 자주 실패해 case 1의 머리 좌표 조건을 영영 못 채우고 "대상이 너무 가까워요"만
 * 반복되는 문제 대책. 한 프레임만 잠깐 넘는 건(검출 튐) 무시한다.
 *
 * case 1(줌이 불가능한 극근접)에서는 일반 구도 판정(READY, "좋아요" 음성)도 도달로
 * 인정한다(사용자 요청 2026-08-28) — 머리 목표 밴드가 일반 READY 기준보다 좁아 "좋아요는
 * 뜨는데 촬영은 안 된다"는 불일치가 있었다. case 2 에서는 인정하지 않는다 — 아직 멀리
 * 있어도 일반 구도만 맞으면 줌을 걸기도 전에 곧장 촬영돼버리기 때문(사용자 요청 2026-08-28
 * — "거기까지 안 가고 멀어도 지금 촬영이 자꾸 되잖아 클로즈업을 안 하고").
 */
internal class PersonFramingController {

    internal sealed interface Action {
        object None : Action
        /** 10% 줌인 요청 — 쿨다운 중이면 호출부가 무시해도 된다. */
        object RequestZoomStep : Action
        /** 목표를 [HOLD_MS] 동안 유지 — 자동촬영을 요청한다. 세션당 1회만. */
        object Capture : Action
    }

    internal data class Outcome(
        val action: Action = Action.None,
        /** 짧은 확정 진동 1회 — case 1: 좌표 도달, case 2: 줌 시작/줌 종료(도달) 경계. */
        val vibrate: Boolean = false,
    )

    private var holdSinceMs: Long? = null
    private var zoomStarted = false
    private var fired = false
    private var closeEnoughSinceMs: Long? = null

    /** 새 인물 세션 시작 — 이전 세션의 진행 상태를 지운다. */
    fun reset() {
        holdSinceMs = null
        zoomStarted = false
        fired = false
        closeEnoughSinceMs = null
    }

    /**
     * 매 판정마다 호출한다.
     *
     * @param bboxFitsFrame false 면 case 1(바운딩 박스가 화면을 벗어남), true 면 case 2.
     * @param headY 코(머리 대용) 정규화 y — 미검출이면 null.
     * @param footY 양 발목 평균(발 대용) 정규화 y — 미검출이면 null.
     * @param bboxHeightRatio 사람 bbox 높이(프레임 대비 0..1) — 미검출이면 null.
     *        [CLOSE_ENOUGH_HEIGHT_RATIO] 이상이면 머리/발 좌표를 못 잡아도(극근접에서 포즈
     *        랜드마크가 자주 실패) 곧장 목표 도달로 보고 줌도 멈춘다(사용자 요청 2026-08-28 —
     *        "자꾸 피사체가 가까이 있어요 라고 뜬다").
     * @param generalReady 일반 구도 판정([ReadinessVerdict.ready], "좋아요" 음성과 같은 조건) —
     *        case 1(bboxFitsFrame=false)에서만 도달 인정에 쓴다. case 2 에서는 무시한다 —
     *        아직 멀리 있어도 인정하면 줌을 걸기도 전에 촬영돼버리기 때문(사용자 요청
     *        2026-08-28).
     */
    fun onJudgment(
        bboxFitsFrame: Boolean,
        headY: Float?,
        footY: Float?,
        bboxHeightRatio: Float?,
        generalReady: Boolean,
        nowMs: Long,
    ): Outcome {
        if (fired) return Outcome()

        // 80% 이상이 잠깐 한 프레임만 튀어도 바로 멈추지 않도록 [CLOSE_ENOUGH_HOLD_MS] 이상
        // 연속으로 유지돼야 인정한다(사용자 요청 2026-08-28 — "80% 이상으로 1초 이상 그래야
        // 줌을 멈추게 해줘").
        val rawCloseEnough = bboxHeightRatio != null && bboxHeightRatio >= CLOSE_ENOUGH_HEIGHT_RATIO
        val closeEnough = if (rawCloseEnough) {
            val since = closeEnoughSinceMs ?: nowMs.also { closeEnoughSinceMs = it }
            nowMs - since >= CLOSE_ENOUGH_HOLD_MS
        } else {
            closeEnoughSinceMs = null
            false
        }
        // generalReady 는 case 1(줌 자체가 불가능한 극근접)에서만 도달로 인정한다 — case 2 에서
        // 허용하면 아직 멀리 있어도(일반 구도만 맞으면) 줌을 걸기도 전에 곧장 촬영돼버린다
        // (사용자 요청 2026-08-28 — "거기까지 안 가고 멀어도 지금 촬영이 자꾸 되잖아").
        val onTarget = closeEnough || if (!bboxFitsFrame) {
            generalReady || (headY != null && headY in CASE1_HEAD_MIN..CASE1_HEAD_MAX)
        } else {
            headY != null && headY in CASE2_HEAD_MIN..CASE2_HEAD_MAX &&
                footY != null && footY in CASE2_FOOT_MIN..CASE2_FOOT_MAX
        }

        if (!onTarget) {
            holdSinceMs = null
            if (!bboxFitsFrame || closeEnough) return Outcome() // case 1, 또는 이미 충분히 큼 — 줌 없음
            val firstStep = !zoomStarted
            zoomStarted = true
            return Outcome(Action.RequestZoomStep, vibrate = firstStep)
        }

        val since = holdSinceMs ?: nowMs.also { holdSinceMs = it }
        val justReached = since == nowMs
        if (nowMs - since >= HOLD_MS) {
            fired = true
            return Outcome(Action.Capture, vibrate = justReached)
        }
        return Outcome(vibrate = justReached)
    }

    companion object {
        // case 1 — 화면을 벗어난 경우: 극근접이라 줌은 못 걸어도, 머리는 일반 인물 사진처럼
        // 위쪽 여백에 오면 충분하다(사용자 요청 2026-08-28 — "얼굴만 0.1~0.3 사이에 좌표가
        // 있으면 그냥 자동촬영"). 위=0, 아래=1.
        const val CASE1_HEAD_MIN = 0.20f
        const val CASE1_HEAD_MAX = 0.40f

        // case 2 — 화면 안에 다 들어온 경우: 머리는 위쪽 여백, 발은 아래쪽에 오도록 줌인한다.
        // 2026-08-28 실기기 튜닝 결과로 고정(사용자 요청 — "줌은 이제 안 건드려도 돼 지금으로
        // 픽스해줘").
        const val CASE2_HEAD_MIN = 0.00f
        const val CASE2_HEAD_MAX = 0.15f
        const val CASE2_FOOT_MIN = 0.60f
        const val CASE2_FOOT_MAX = 0.80f

        /** 목표 도달 후 이만큼 유지되면 자동촬영 (사용자 요청 2026-08-28 — "3초 안에"). */
        const val HOLD_MS = 3_000L

        /** bbox 높이가 프레임의 이 비율 이상이면 정밀 좌표 없이도 "충분히 가까움". */
        const val CLOSE_ENOUGH_HEIGHT_RATIO = 0.80f

        /** [CLOSE_ENOUGH_HEIGHT_RATIO] 이상이 이만큼 연속 유지돼야 인정한다(잠깐 튀는 것 방지). */
        const val CLOSE_ENOUGH_HOLD_MS = 1_000L
    }
}
