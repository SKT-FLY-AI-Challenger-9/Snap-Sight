package com.example.snap_sight.ux

/**
 * 인물 프레이밍 (2026-08-28) — 인물 세션에서 바운딩 박스가 화면에 다 들어오는지로 분기한다
 * (사용자 요청: "인물이라고 판정됐을 때 바운딩 박스가 화면에 다 안에 안 들어오면 분기").
 * 좌우는 기존 [GuidancePolicy] 3x5 시계 안내가 그대로 맡고, 이 클래스는 상하/줌 축만 본다.
 *
 *  1. bbox 가 화면을 벗어남([CanonicalReadinessEvaluator]의 VISIBILITY 블로커) — 너무 가까워
 *     전신이 안 들어오는 경우. 줌은 아예 걸지 않는다. 머리(코) 좌표가 [CASE1_HEAD_MIN]..
 *     [CASE1_HEAD_MAX] 사이면(위=0,아래=1) 목표 도달로 보고 진동 1회.
 *  2. bbox 가 화면 안에 다 들어옴 — 10%씩 점차 줌인([RequestZoomStep], 실제 반영 여부는
 *     [AutoZoomController] 쿨다운이 결정)하면서 머리 [CASE2_HEAD_MIN]..[CASE2_HEAD_MAX],
 *     발(양 발목 평균) [CASE2_FOOT_MIN]..[CASE2_FOOT_MAX] 에 들어오면 목표 도달.
 *     진동은 줌인을 "시작할 때"(첫 스텝) 1회, "끝났을 때"(목표 도달) 1회 — 두 경계에서만.
 *
 * 두 경우 모두 목표 도달 후 [HOLD_MS](3초) 동안 유지되면 [Capture] 를 1회만 반환한다 —
 * READY 유지를 요구하는 기존 [AutoCaptureArbiter] 와 달리 인물 프레이밍 전용의 짧은 확정
 * 창이다. [GuidancePolicy] 처럼 android.* 의존이 없어 시각(nowMs)을 주입받아 테스트한다.
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

    /** 새 인물 세션 시작 — 이전 세션의 진행 상태를 지운다. */
    fun reset() {
        holdSinceMs = null
        zoomStarted = false
        fired = false
    }

    /**
     * 매 판정마다 호출한다.
     *
     * @param bboxFitsFrame false 면 case 1(바운딩 박스가 화면을 벗어남), true 면 case 2.
     * @param headY 코(머리 대용) 정규화 y — 미검출이면 null.
     * @param footY 양 발목 평균(발 대용) 정규화 y — 미검출이면 null.
     */
    fun onJudgment(bboxFitsFrame: Boolean, headY: Float?, footY: Float?, nowMs: Long): Outcome {
        if (fired) return Outcome()

        val onTarget = if (!bboxFitsFrame) {
            headY != null && headY in CASE1_HEAD_MIN..CASE1_HEAD_MAX
        } else {
            headY != null && headY in CASE2_HEAD_MIN..CASE2_HEAD_MAX &&
                footY != null && footY in CASE2_FOOT_MIN..CASE2_FOOT_MAX
        }

        if (!onTarget) {
            holdSinceMs = null
            if (!bboxFitsFrame) return Outcome() // case 1 — 줌 없음, 그냥 대기
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
        // case 1 — 화면을 벗어난 경우: 최소한 머리가 보이는 지점까지만 요구한다(이상적인
        // 여백보다는 "일단 들어옴"을 우선). 위=0, 아래=1.
        const val CASE1_HEAD_MIN = 0.70f
        const val CASE1_HEAD_MAX = 0.90f

        // case 2 — 화면 안에 다 들어온 경우: 머리는 위쪽 여백, 발은 아래쪽에 오도록 줌인한다.
        const val CASE2_HEAD_MIN = 0.10f
        const val CASE2_HEAD_MAX = 0.20f
        const val CASE2_FOOT_MIN = 0.60f
        const val CASE2_FOOT_MAX = 0.80f

        /** 목표 도달 후 이만큼 유지되면 자동촬영 (사용자 요청 2026-08-28 — "3초 안에"). */
        const val HOLD_MS = 3_000L
    }
}
