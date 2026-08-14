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
     */
    fun update(detections: List<Detection>, timestampS: Double? = null): List<TrackedObject>

    /** 새 카메라/영상 세션 시작 시 호출. track 상태와 ID 카운터를 초기화한다. */
    fun reset()
}
