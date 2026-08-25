package com.example.snap_sight.ux

/**
 * 자동촬영 중재자 — "명확한 대상"이 지정된 세션(등록 인물·사물 이름 또는 taxonomy
 * objectLabel)에서 그 대상이 충분히 오래 **실제로 관측**되면 셔터를 세션당 1회 요청한다.
 * 구도 READY 까지는 요구하지 않는다 — "잡히면 찍는다"가 요구사항이고, READY 조건(중앙
 * 정렬·크기 목표·안정화)을 걸면 구도가 안 나오는 장면에서 영영 발동하지 않는다
 * (2026-08-24 실기기: 노트북 인식돼도 미발동). 예고 발화·카운트다운 없이 기존 셔터
 * 효과음만 낸다.
 *
 * 발동 조건 (2026-08-25 강화 — "잠깐 스친 피사체에 발동" 대책):
 *  1. 첫 실관측(fresh)부터 마지막 실관측까지 [AUTO_CAPTURE_HOLD_MS] 이상 —
 *     tracker coasting(PREDICTED, 0.7초) + 편차 hold(HELD, 0.6초)가 이어붙은 시간은
 *     끊김으로 치지 않지만 **유지 시간으로 쌓이지도 않는다**. 예전엔 detected 플래그가
 *     이 추정 관측까지 포함해, 실제로는 한두 프레임 스친 대상도 coasting+hold 사슬만으로
 *     1.5초를 채워 발동하는 일이 있었다.
 *  2. 그 사이 실관측이 [MIN_FRESH_OBSERVATIONS]번 이상 — 발열로 detector 주기가 길어져도
 *     "몇 번은 진짜로 봤다"를 보장한다.
 *  3. 발동하는 판정 자체가 실관측 — 예측 위치를 보고 찍지 않는다.
 *
 * detected 가 한 판정이라도 false 면(추정 사슬까지 끊긴 진짜 유실) 처음부터 다시 시작한다.
 *
 * 풍경("풍경 찍을래")·일반 촬영("사진 찍을래")은 eligible=false 로 들어와 절대 발동하지
 * 않는다 — 그 모드들은 두 번 탭 수동 촬영 그대로다.
 *
 * [GuidancePolicy] 처럼 android.* 의존이 없고 시각(nowMs)을 주입받아 단위 테스트한다.
 * 판정 스레드(분석)에서 호출되므로 @Synchronized 로 지킨다.
 */
internal class AutoCaptureArbiter {

    private var firstFreshMs: Long? = null
    private var freshCount = 0
    private var fired = false

    /** 새 세션·새 타겟 세대 시작 — 유지 시간과 "이미 찍었음" 상태를 지운다. */
    @Synchronized
    fun reset() {
        firstFreshMs = null
        freshCount = 0
        fired = false
    }

    /**
     * 매 판정마다 호출한다. true 를 반환한 딱 한 번만 셔터를 요청해야 하며,
     * 이후에는 [reset] 전까지 다시 true 를 반환하지 않는다.
     *
     * @param eligible 자동촬영 대상 세션인가 — COMPOSITION 모드이고 등록 이름 또는
     *        objectLabel 로 대상이 명확히 지정된 경우에만 true.
     * @param detected 이번 판정에서 지정 대상이 화면에 잡혀 있는가(추정 포함) — 호출부가
     *        시선 게이트(셀카) 통과까지 합쳐서 넘긴다. false 면 유지 시간이 리셋된다.
     * @param fresh 이번 판정이 **실제 픽셀 관측**(detector keyframe 의 FRESH)인가 —
     *        tracker 예측(PREDICTED)·편차 hold(HELD)·held 재방출(analyzed=false)은 false.
     *        유지 시간과 관측 횟수는 fresh 판정으로만 쌓인다.
     */
    @Synchronized
    fun onJudgment(eligible: Boolean, detected: Boolean, fresh: Boolean, nowMs: Long): Boolean {
        if (!eligible || !detected) {
            firstFreshMs = null
            freshCount = 0
            return false
        }
        if (fired) return false
        if (!fresh) return false
        val since = firstFreshMs ?: nowMs.also { firstFreshMs = it }
        freshCount++
        if (nowMs - since < AUTO_CAPTURE_HOLD_MS) return false
        if (freshCount < MIN_FRESH_OBSERVATIONS) return false
        fired = true
        return true
    }

    companion object {
        /**
         * 첫 실관측 → 발동 실관측 사이 최소 시간 — 존재 확인 진동의 유지 조건
         * ([GuidancePolicy.PRESENCE_VIBRATION_AFTER_MS])과 같은 1.5초. 사용자 감각으로는
         * "진동이 손에 느껴지는 순간 = 곧 찍힌다"로 이어진다. 단 진동과 달리 추정 관측은
         * 시간으로 쳐주지 않으므로 실제 발동은 진동보다 늦거나 같다.
         */
        const val AUTO_CAPTURE_HOLD_MS = 1_500L

        /**
         * 유지 시간 안에 요구하는 최소 실관측 횟수. LOCKED cadence(기준 150ms×2=300ms)로
         * 1.5초면 실관측 5~6회가 정상이라 4회는 여유가 있고, 발열로 주기가 1초까지 늘어나면
         * 그만큼 발동이 늦어질 뿐 막히지는 않는다.
         */
        const val MIN_FRESH_OBSERVATIONS = 4
    }
}
