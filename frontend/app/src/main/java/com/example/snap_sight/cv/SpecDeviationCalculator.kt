package com.example.snap_sight.cv

/**
 * [DeviationCalculator] 확장 자리(②의 `Deviation.kt`)의 첫 실구현.
 *
 * tracking·선택이 끝난 프레임에서 타겟 1개를 골라 순수 기하 편차([FramingDeviation])를
 * 계산한다. 파이프라인에 꽂히는 지점은 [SnapSightFrameProcessor.create] 의
 * `deviationCalculator` 파라미터이고, 결과는 [CvFrameOutput.deviation] 으로 흘러나온다.
 *
 * 타겟 선택 규칙:
 *  - landscape 의도는 겨냥할 피사체가 없음 → null (docs/deviation-interface.md 의 "대상 없음" 규약)
 *  - selection 후보 중 최고 신뢰도 1개. 후보가 없으면 null (= 타겟 유실, LOST 후보)
 */
class SpecDeviationCalculator : DeviationCalculator {

    override fun compute(selection: TargetSelection, spec: TargetSpec?): FramingDeviation? {
        if (spec?.subjectType == TargetSpec.SubjectType.LANDSCAPE) return null
        val target = selection.candidates.maxByOrNull { it.confidence } ?: return null
        return FramingDeviation(
            trackId = target.trackId,
            offsetX = ((target.bbox.centerX - 0.5f) * 2f).coerceIn(-1f, 1f),
            offsetY = ((target.bbox.centerY - 0.5f) * 2f).coerceIn(-1f, 1f),
            areaRatio = target.bbox.area.coerceIn(0f, 1f),
        )
    }
}

/**
 * 기하 편차([FramingDeviation]) → 판정 편차([DeviationResult]) 해석.
 *
 * `backend/judgment/deviation.py`(PR #27) / `docs/deviation-interface.md` 계약의 Kotlin
 * 이식이며, 이 파일이 런타임 정본이다 (실시간 판정은 온디바이스 — Notion 파이프라인 ④).
 *
 * 부호 규약 (계약 문서와 동일):
 *  - [DeviationResult.xDeviation] = center_x − 0.5 (−0.5..+0.5). 음수 = 타겟이 왼쪽, 양수 = 오른쪽
 *  - [DeviationResult.sizeDeviation] = area_ratio − 프레이밍별 목표비. 음수 = 너무 멂, 양수 = 너무 가까움
 */
object DeviationJudgment {

    /**
     * 프레이밍별 목표 면적비 — 실측 검증 전 1차 추정치.
     * `docs/deviation-interface.md` 의 값과 반드시 일치시킨다 (테스트로 고정).
     */
    val TARGET_AREA_RATIO: Map<TargetSpec.Framing, Float> = mapOf(
        TargetSpec.Framing.CLOSEUP to 0.30f,
        TargetSpec.Framing.FULL_BODY to 0.12f,
        TargetSpec.Framing.WIDE to 0.04f,
    )

    // READY(촬영 가능) 후보 판정 임계값 — 이슈 #42 실기기 편차 분포로 1차 캘리브레이션됨
    // (|x| p50 0.123, |size| p50 0.086 관측 → 기존 0.1/0.05로는 READY 도달률 3%).
    // 정본은 ⑥의 docs/ux/guidance-state-schema.md (CENTERED 허용 오차)이며 그 값에 맞춘다.
    const val READY_MAX_ABS_X_DEVIATION = 0.15f
    const val READY_MAX_ABS_SIZE_DEVIATION = 0.10f

    /**
     * @param deviation 파이프라인이 계산한 기하 편차. null = 겨냥할 대상 없음
     *                  (타겟 유실과 landscape 의도 모두 포함 — LOST 후보)
     * @param framing   의도 프레이밍. 의도 없는 세션은 기본값 FULL_BODY 로 판정
     */
    fun judge(deviation: FramingDeviation?, framing: TargetSpec.Framing): DeviationResult {
        if (deviation == null) {
            return DeviationResult(subjectDetected = false, xDeviation = null, sizeDeviation = null)
        }
        return DeviationResult(
            subjectDetected = true,
            // FramingDeviation.offsetX 는 -1..1 스케일 → 계약(center_x − 0.5)의 -0.5..0.5 로 환산
            xDeviation = deviation.offsetX / 2f,
            sizeDeviation = deviation.areaRatio - TARGET_AREA_RATIO.getValue(framing),
        )
    }

    /** 두 편차가 모두 임계값 안 = READY 후보. 최종 READY 판정·자동 셔터는 후속 이슈. */
    fun isReadyCandidate(result: DeviationResult): Boolean {
        val x = result.xDeviation ?: return false
        val size = result.sizeDeviation ?: return false
        return kotlin.math.abs(x) <= READY_MAX_ABS_X_DEVIATION &&
            kotlin.math.abs(size) <= READY_MAX_ABS_SIZE_DEVIATION
    }
}

/**
 * 판정 편차 결과. [subjectDetected] 가 false 면 두 편차는 반드시 null,
 * true 면 반드시 채워져 있다 — 계약 위반은 생성 시점에 막는다.
 */
data class DeviationResult(
    val subjectDetected: Boolean,
    val xDeviation: Float?,
    val sizeDeviation: Float?,
) {
    init {
        if (subjectDetected) {
            require(xDeviation != null && sizeDeviation != null) {
                "subjectDetected=true 인 경우 편차 값은 비어 있으면 안 됩니다."
            }
        } else {
            require(xDeviation == null && sizeDeviation == null) {
                "subjectDetected=false 인 경우 편차 값은 모두 비어 있어야 합니다."
            }
        }
    }
}

/** 판정 편차 수신 계약 (⑥ 연결 지점). CV 분석 스레드에서 호출될 수 있다. */
fun interface DeviationListener {
    fun onDeviation(result: DeviationResult)
}
