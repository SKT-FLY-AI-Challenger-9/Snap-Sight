// 이 파일: "노트북 찍어줘"라고 했는데 셔터 순간 노트북이 화면에 없었을 때,
// 촬영은 그대로 진행하되 결과 안내에 "못 찾았다"는 사실을 담기 위한 판정.
// 셔터를 막지 않는다 — 알리기만 한다 (2026-08-21 사용자 결정).
package com.example.snap_sight.camera

import com.example.snap_sight.cv.TargetSpec

object MissingSubjectNotice {

    /**
     * 셔터 순간 "요청한 피사체가 화면에 없었다"고 말해도 되는 상황이면 그 피사체의
     * 한글 호칭을, 아니면 null 을 반환한다 (순수 로직, JVM 테스트 대상).
     *
     * null 인 경우 — 알릴 근거가 없다:
     *  - 의도가 없거나(status!=ok 포함) 풍경(landscape) 의도
     *  - CV 판정이 신선하지 않다 (모델 자산 없음, CV 멈춤 등 — 근거 없이 단정하면 안 된다)
     *  - 피사체가 실제로 보였다
     *
     * @param spec            현재 세션 의도
     * @param subjectDetected 최근 CV 판정의 피사체 발견 여부
     * @param hasFreshVerdict [subjectDetected] 가 신선한 판정인지
     * @param koreanLabels    canonical 라벨 → 한글 표기 (없는 라벨은 영문 그대로)
     */
    fun targetNameIfMissing(
        spec: TargetSpec?,
        subjectDetected: Boolean,
        hasFreshVerdict: Boolean,
        koreanLabels: Map<String, String>,
    ): String? {
        if (spec == null || !spec.isActionable) return null
        if (spec.subjectType.sceneOnly) return null
        if (!hasFreshVerdict) return null
        if (subjectDetected) return null
        return targetName(spec, koreanLabels)
    }

    /** 안내에 쓸 타겟 호칭 — 의도 종류별 한글 이름. */
    internal fun targetName(spec: TargetSpec, koreanLabels: Map<String, String>): String = when {
        spec.subjectType == TargetSpec.SubjectType.PERSON -> "사람"
        spec.objectLabel != null ->
            koreanLabels[spec.objectLabel.trim().lowercase()] ?: spec.objectLabel
        else -> "피사체"
    }
}
