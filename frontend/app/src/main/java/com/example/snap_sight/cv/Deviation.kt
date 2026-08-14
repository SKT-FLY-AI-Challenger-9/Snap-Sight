package com.example.snap_sight.cv

/**
 * 편차 계산의 **확장 자리** (리드미 단계 5 — 연속 피드백의 입력).
 *
 * 리드미 원안은 ③ 백엔드가 편차를 계산하지만, 매 프레임 bbox 를 서버까지 왕복시키면
 * 햅틱 피드백 지연을 감당할 수 없다. 그래서 편차는 CV 와 같은 온디바이스 프로세스에 두되,
 * **파이프라인과는 분리된 모듈**로 유지해 규칙이 바뀌어도 CV 를 건드리지 않게 한다.
 *
 * 현재 기본값은 [NoDeviationCalculator] — 항상 null 을 돌려주고 아무 판단도 하지 않는다.
 * 규칙(중앙 정렬 허용 오차, framing 별 목표 크기 등)이 정해지면 이 인터페이스만 구현해서
 * [SnapSightFrameProcessor] 에 넘기면 된다. 순수 기하값(중심 오프셋, 면적비)은
 * [BoundingBox.centerX] / [BoundingBox.area] 에 이미 있다.
 */
data class FramingDeviation(
    /** 기준으로 삼은 객체. 대상이 특정되지 않았으면 null. */
    val trackId: Int?,
    /** 프레임 중심 기준 좌우 오프셋. -1(왼쪽 끝) ~ +1(오른쪽 끝), 0 이 정중앙. */
    val offsetX: Float,
    /** 프레임 중심 기준 상하 오프셋. -1(위) ~ +1(아래), 0 이 정중앙. */
    val offsetY: Float,
    /** 피사체 면적 / 프레임 면적. framing(closeup/full_body/wide) 판정의 입력. */
    val areaRatio: Float,
)

interface DeviationCalculator {
    /**
     * @param selection tracking·선택이 끝난 현재 프레임 결과
     * @param spec      의도. null 이면 목표가 없는 상태다.
     * @return 피드백에 쓸 편차. 판단할 근거가 없으면 null.
     */
    fun compute(selection: TargetSelection, spec: TargetSpec?): FramingDeviation?
}

/** 편차 규칙이 정해지기 전까지의 명시적 placeholder. */
class NoDeviationCalculator : DeviationCalculator {
    override fun compute(selection: TargetSelection, spec: TargetSpec?): FramingDeviation? = null
}
