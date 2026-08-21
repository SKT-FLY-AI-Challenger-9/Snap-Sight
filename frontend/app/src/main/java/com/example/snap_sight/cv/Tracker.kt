package com.example.snap_sight.cv

/**
 * 다중 객체 tracker 계약. `ai/on_device_cv/trackers/base.py` 대응.
 *
 * 구현체는 프레임마다 [update] 로 검출을 받아 스트림 내내 유지되는 `track_id` 를 붙인다.
 * 검출이 누락된 프레임의 "예측만 된" track 은 반환하지 않는다 —
 * confidence 의미가 불명확해서 공개 계약에 넣을 수 없기 때문.
 */
interface Tracker {

    /**
     * @param timestampS 카메라 프레임 시각(초). 한 스트림 안에서는 항상 주거나 항상 생략해야 하고,
     *                   줄 때는 단조 증가해야 한다. 생략하면 `update()` 1회 = 1시간 단위로 본다.
     * @param motionHint 직전 [update] 이후 **카메라 이동으로 인한 화면 내 객체들의 예상 이동량**
     *                   (normalized 좌표). 화면을 못 보는 사용자가 카메라를 크게 휘두르는 앱 특성상
     *                   IoU 매칭이 끊기는 주원인이라, 구현체는 예측 위치를 이만큼 보정할 수 있다.
     *                   null = 보정 없음 (기존 동작과 동일). `docs/feature-expansion-plan.md` 기능 1-C.
     */
    fun update(
        detections: List<Detection>,
        timestampS: Double? = null,
        motionHint: MotionHint? = null,
    ): List<TrackedObject>

    /** 새 카메라/영상 세션 시작 시 호출. track 상태와 ID 카운터를 초기화한다. */
    fun reset()
}

/**
 * 프레임 간 전역(카메라 기인) 이동량. normalized 프레임 좌표계 기준 —
 * 카메라가 오른쪽으로 회전하면 화면 속 객체는 왼쪽으로 이동하므로 [dx] 는 음수다.
 * 값 산출은 소비자(자이로 등) 책임이고, tracker 는 의미를 해석하지 않는다.
 */
data class MotionHint(val dx: Float, val dy: Float) {
    init {
        require(dx.isFinite() && dy.isFinite()) { "MotionHint must be finite" }
    }
}
