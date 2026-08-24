package com.example.snap_sight.ux

/**
 * 자동촬영 중재자 — "명확한 대상"이 지정된 세션(등록 인물·사물 이름 또는 taxonomy
 * objectLabel)에서 그 대상이 [AUTO_CAPTURE_HOLD_MS] 이상 연속으로 화면에 잡혀 있으면
 * 셔터를 세션당 1회 요청한다. 구도 READY 까지는 요구하지 않는다 — "잡히면 찍는다"가
 * 요구사항이고, READY 조건(중앙 정렬·크기 목표·안정화)을 걸면 구도가 안 나오는 장면에서
 * 영영 발동하지 않는다 (2026-08-24 실기기: 노트북 인식돼도 미발동). 예고 발화·카운트다운
 * 없이 기존 셔터 효과음만 낸다.
 *
 * 풍경("풍경 찍을래")·일반 촬영("사진 찍을래")은 eligible=false 로 들어와 절대 발동하지
 * 않는다 — 그 모드들은 두 번 탭 수동 촬영 그대로다.
 *
 * detector flicker 로 검출이 한 판정이라도 끊기면 유지 시간이 0부터 다시 시작된다 —
 * 존재 확인 진동([GuidancePolicy.presenceActions])과 같은 유지 규칙.
 *
 * [GuidancePolicy] 처럼 android.* 의존이 없고 시각(nowMs)을 주입받아 단위 테스트한다.
 * 판정 스레드(분석)에서 호출되므로 @Synchronized 로 지킨다.
 */
internal class AutoCaptureArbiter {

    private var detectedSinceMs: Long? = null
    private var fired = false

    /** 새 세션·새 타겟 세대 시작 — 유지 시간과 "이미 찍었음" 상태를 지운다. */
    @Synchronized
    fun reset() {
        detectedSinceMs = null
        fired = false
    }

    /**
     * 매 판정마다 호출한다. true 를 반환한 딱 한 번만 셔터를 요청해야 하며,
     * 이후에는 [reset] 전까지 다시 true 를 반환하지 않는다.
     *
     * @param eligible 자동촬영 대상 세션인가 — COMPOSITION 모드이고 등록 이름 또는
     *        objectLabel 로 대상이 명확히 지정된 경우에만 true.
     * @param detected 이번 판정에서 지정 대상이 화면에 잡혀 있는가 — 호출부가
     *        시선 게이트(셀카) 통과까지 합쳐서 넘긴다.
     */
    @Synchronized
    fun onJudgment(eligible: Boolean, detected: Boolean, nowMs: Long): Boolean {
        if (!eligible || !detected) {
            detectedSinceMs = null
            return false
        }
        if (fired) return false
        val since = detectedSinceMs ?: nowMs.also { detectedSinceMs = it }
        if (nowMs - since < AUTO_CAPTURE_HOLD_MS) return false
        fired = true
        return true
    }

    companion object {
        /**
         * 검출 연속 유지 시간 — 존재 확인 진동의 유지 조건
         * ([GuidancePolicy.PRESENCE_VIBRATION_AFTER_MS])과 같은 1.5초. 사용자 감각으로는
         * "진동이 손에 느껴지는 순간 = 곧 찍힌다"로 이어진다.
         */
        const val AUTO_CAPTURE_HOLD_MS = 1_500L
    }
}
