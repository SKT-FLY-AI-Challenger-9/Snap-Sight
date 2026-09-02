package com.example.snap_sight.cv

/**
 * ② CV 모듈이 프레임마다 내보내는 결과.
 *
 * **소비자가 의존해야 하는 계약은 [objectsJson] / [frameResult] 하나뿐이다.**
 * [selection] 은 의도 기반 후보 선택 결과이고, [deviation] 은 아직 계산기가 붙지 않아 null 이다.
 * 새 필드를 추가할 때도 기존 objects 스키마는 절대 바꾸지 않는다.
 */
data class CvFrameOutput(
    /** 공개 계약: `{"objects":[{track_id,label,confidence,bbox}]}` */
    val frameResult: FrameResult,
    /** 이 결과가 나온 프레임의 시각 (`System.currentTimeMillis()` 기준). */
    val timestampMs: Long,
    /**
     * false 면 frame-stride 로 건너뛴 프레임이라 직전 분석 결과를 그대로 재사용한 것이다.
     * (Python demo 의 selection JSONL `analyzed` 와 같은 의미)
     */
    val analyzed: Boolean,
    /** 실제 detector 관측, tracker 예측, 마지막 값 유지 중 어느 출처인지. */
    val observationFreshness: ObservationFreshness = ObservationFreshness.FRESH,
    /** Target-intent generation used to select and judge this output. */
    val targetIntentGeneration: Long = 0L,
    /** 현재 세션의 의도. 항상 null 일 수 있다 (마이크 권한 없음, 발화 생략). */
    val targetSpec: TargetSpec? = null,
    /** Registered face/object name used to filter this output, if any. */
    val targetIdentityName: String? = null,
    /**
     * 의도 기반 후보 선택 결과 ([Objects365TargetSelector]).
     * 의도 없는 세션이나 pass-through fallback 에서는 `DISABLED` + 전체 객체.
     */
    val selection: TargetSelection? = null,
    /** 편차 계산 결과. 계산기가 붙기 전까지 null. */
    val deviation: FramingDeviation? = null,
    /**
     * 얼굴 신원 (track_id → 등록 인물 이름, 기능 2). 임베더·등록 인물이 없으면 항상 빈 맵.
     * ⚠️ 이름은 기기 로컬 전용 — 서버 업로드·objects JSON 에 절대 섞지 않는다.
     */
    val identities: Map<Int, String> = emptyMap(),
) {
    val objects: List<TrackedObject> get() = frameResult.objects

    /** ⑤/③ 로 넘기는 안정 JSON. 확장 필드는 여기에 섞지 않는다. */
    fun objectsJson(): String = frameResult.toJson()
}

/**
 * ② → ⑤/⑥ 결과 수신 계약.
 *
 * 스레딩: [onFrameResult] 는 CameraX 분석 전용 스레드에서 **동기로** 호출된다.
 * 메인 스레드가 아니며, 여기서 오래 걸리면 다음 프레임이 드롭된다.
 * UI 갱신은 구현체가 직접 메인 스레드로 넘겨야 한다.
 */
fun interface ObjectStreamListener {
    fun onFrameResult(output: CvFrameOutput)
}
