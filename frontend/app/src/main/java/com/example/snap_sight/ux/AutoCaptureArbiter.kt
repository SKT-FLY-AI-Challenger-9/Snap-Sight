package com.example.snap_sight.ux

/**
 * 자동촬영 중재자 — "명확한 대상"이 지정된 세션(등록 인물·사물 이름 또는 taxonomy
 * objectLabel)에서 구도까지 READY(=화면 "지금이에요!" 문구·음성 "좋아요. 촬영할 수
 * 있어요."와 같은 조건)인 판정이 [MIN_READY_HOLD_MS] 이상 끊기지 않고 이어지면 셔터를
 * 세션당 1회 요청한다. 예고 발화·카운트다운 없이 기존 셔터 효과음만 낸다.
 *
 * 발동 조건 (2026-08-26 개편):
 *  1. 첫 실관측(fresh) READY 판정부터 [MIN_READY_HOLD_MS] 이상 — "잠깐 노트북이
 *     지나가서 READY가 한 번 뜨자마자 바로 찍힌다"는 문제 대책. READY 안내("좋아요")가
 *     시작되고도 몇 초 더 자세를 유지해야 실제로 찍힌다.
 *  2. 그 사이 실관측이 [MIN_CONSECUTIVE_READY_FRESH]번 이상 — detector 주기가 늘어져도
 *     "몇 번은 진짜로 READY를 봤다"를 보장하는 하한선(보통은 1의 시간 조건이 훨씬 크다).
 *  3. 욜로가 매 프레임 탐지하지 않아 끼는 추정 판정(PREDICTED/HELD, fresh=false)은
 *     스트릭을 끊지도 시간에 보태지도 않고 그냥 건너뛴다. 대신 fresh 판정에서 READY가
 *     아니면(진짜 구도 이탈·유실이 실관측으로 확인된 것) 스트릭을 처음부터 다시 시작한다.
 *  4. eligible이 끊기면(세션·타겟 세대 교체 등) 스트릭도 무효.
 *
 * 예전엔("잡히면 찍는다") 구도 READY를 요구하지 않았으나(2026-08-24: 노트북 인식돼도
 * 구도가 안 잡혀 영영 미발동했던 사례), 이번엔 반대로 정밀도를 우선해 READY 유지 조건을
 * 도입한다 — 구도가 전혀 안 잡히는 장면에서는 자동촬영이 발동하지 않을 수 있음을 감수한다.
 *
 * [GuidancePolicy] 처럼 android.* 의존이 없고 시각(nowMs)을 주입받아 단위 테스트한다.
 * 판정 스레드(분석)에서 호출되므로 @Synchronized 로 지킨다.
 */
internal class AutoCaptureArbiter {

    private var firstReadyMs: Long? = null
    private var consecutiveReadyFresh = 0
    private var fired = false

    /** 새 세션·새 타겟 세대 시작 — 유지 시간·스트릭과 "이미 찍었음" 상태를 지운다. */
    @Synchronized
    fun reset() {
        firstReadyMs = null
        consecutiveReadyFresh = 0
        fired = false
    }

    /**
     * 매 판정마다 호출한다. true 를 반환한 딱 한 번만 셔터를 요청해야 하며,
     * 이후에는 [reset] 전까지 다시 true 를 반환하지 않는다.
     *
     * @param eligible 자동촬영 대상 세션인가 — COMPOSITION 모드이고 등록 이름 또는
     *        objectLabel 로 대상이 명확히 지정된 경우에만 true.
     * @param ready 이번 판정의 구도가 READY 인가 — [GuidanceFeedback.processDeviation]의
     *        같은 판정. 피사체 미검출·중앙 정렬 실패·크기 미달 등은 모두 false 로 들어온다.
     * @param fresh 이번 판정이 **실제 픽셀 관측**(detector keyframe 의 FRESH)인가 —
     *        tracker 예측(PREDICTED)·편차 hold(HELD)는 false. false 인 판정은 유지 시간과
     *        스트릭 어느 쪽에도 영향을 주지 않고 그냥 건너뛴다(욜로가 매 프레임 탐지하지
     *        않는 것을 흡수).
     * @param nowMs 이번 판정 시각.
     */
    @Synchronized
    fun onJudgment(eligible: Boolean, ready: Boolean, fresh: Boolean, nowMs: Long): Boolean {
        if (!eligible) {
            firstReadyMs = null
            consecutiveReadyFresh = 0
            return false
        }
        if (fired) return false
        if (!fresh) return false // 추정 판정 — 유지 시간·스트릭 어느 쪽도 건드리지 않고 대기
        if (!ready) {
            firstReadyMs = null
            consecutiveReadyFresh = 0
            return false
        }
        val since = firstReadyMs ?: nowMs.also { firstReadyMs = it }
        consecutiveReadyFresh++
        if (nowMs - since < MIN_READY_HOLD_MS) return false
        if (consecutiveReadyFresh < MIN_CONSECUTIVE_READY_FRESH) return false
        fired = true
        return true
    }

    companion object {
        /**
         * 첫 실관측 READY 판정 → 발동 실관측 사이 최소 시간. "잠깐 노트북이 지나가서
         * 좋아요가 뜨자마자 바로 찍힌다"는 문제 대책 — READY 안내가 시작되고도 이만큼 더
         * 자세를 유지해야 찍힌다 (사용자 요청 2026-08-26 — "조금 한 4초간 기다리면 안 되냐").
         * 추정 판정(coasting/hold)은 이 시간에 쌓이지 않으므로 실제 체감은 이보다 늦다.
         */
        const val MIN_READY_HOLD_MS = 4_000L

        /**
         * 유지 시간 안에 요구하는 최소 "실관측 + READY" 횟수. 대개는 [MIN_READY_HOLD_MS]
         * 쪽이 더 오래 걸려 이 조건은 하한선 역할만 한다 — detector 주기가 아주 길어져도
         * "몇 번은 진짜로 READY를 봤다"를 보장한다.
         */
        const val MIN_CONSECUTIVE_READY_FRESH = 5
    }
}
