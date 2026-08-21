// 이 파일: "지금 카메라를 보고 있는가" 판정의 순수 로직 (셀카 모드, JVM 테스트 대상).
// 3단 판정: ① 머리 방향(Euler X/Y) ② 눈 뜸 확률 ③ 눈 안의 동공 위치(PupilFinder) —
// "얼굴은 정면인데 눈동자만 옆을 보는" 경우까지 잡는다 (2026-08-21 피드백).
package com.example.snap_sight.face

import kotlin.math.abs

object GazeJudge {

    /** 판정 결과 — 무엇 때문에 "안 보고 있다"인지에 따라 안내 문구가 달라진다. */
    enum class Verdict {
        LOOKING,
        /** 고개가 카메라를 향해 있지 않다. */
        HEAD_TURNED,
        /** 눈을 감고 있다. */
        EYES_CLOSED,
        /** 고개는 정면인데 눈동자가 다른 곳을 본다. */
        EYES_AWAY,
    }

    /**
     * @param eulerYawDegrees   좌우 회전 (ML Kit headEulerAngleY). 0 = 정면
     * @param eulerPitchDegrees 상하 회전 (ML Kit headEulerAngleX). 0 = 정면
     * @param leftEyeOpenProbability / rightEyeOpenProbability — 분류 미지원 기기면 null
     * @param pupilHorizontalRatio 눈 영역 안 동공의 가로 위치 (0=안쪽 끝, 1=바깥쪽 끝, 0.5=중앙).
     *        동공을 못 찾았으면 null — 머리 방향·눈 뜸만으로 판정한다 (fail-open)
     */
    fun judge(
        eulerYawDegrees: Float,
        eulerPitchDegrees: Float,
        leftEyeOpenProbability: Float?,
        rightEyeOpenProbability: Float?,
        pupilHorizontalRatio: Float? = null,
    ): Verdict {
        if (abs(eulerYawDegrees) > MAX_ABS_YAW_DEGREES) return Verdict.HEAD_TURNED
        if (abs(eulerPitchDegrees) > MAX_ABS_PITCH_DEGREES) return Verdict.HEAD_TURNED
        // 한쪽 눈 확률만 있어도 판정한다. 둘 다 null 이면(분류 미지원) 눈 뜸 검사는 건너뛴다.
        val eyeOpen = listOfNotNull(leftEyeOpenProbability, rightEyeOpenProbability).maxOrNull()
        if (eyeOpen != null && eyeOpen < MIN_EYE_OPEN_PROBABILITY) return Verdict.EYES_CLOSED
        if (pupilHorizontalRatio != null &&
            (pupilHorizontalRatio < MIN_PUPIL_H_RATIO || pupilHorizontalRatio > MAX_PUPIL_H_RATIO)
        ) {
            return Verdict.EYES_AWAY
        }
        return Verdict.LOOKING
    }

    /** (구버전 호환·간단 검사용) [judge] == LOOKING 인지. */
    fun isLookingAtCamera(
        eulerYawDegrees: Float,
        eulerPitchDegrees: Float,
        leftEyeOpenProbability: Float?,
        rightEyeOpenProbability: Float?,
        pupilHorizontalRatio: Float? = null,
    ): Boolean = judge(
        eulerYawDegrees, eulerPitchDegrees,
        leftEyeOpenProbability, rightEyeOpenProbability, pupilHorizontalRatio,
    ) == Verdict.LOOKING

    // 파라미터 표 (docs/feature-expansion-plan.md 방식 — 리허설에서 이 상수만 조정)
    const val MAX_ABS_YAW_DEGREES = 15f
    const val MAX_ABS_PITCH_DEGREES = 15f
    const val MIN_EYE_OPEN_PROBABILITY = 0.3f

    // 동공이 눈 가로폭에서 이 범위 안(중앙 부근)이면 "카메라를 본다"로 판정.
    // 카메라와 화면이 가까운 셀카 거리 특성상 여유를 둔다.
    const val MIN_PUPIL_H_RATIO = 0.32f
    const val MAX_PUPIL_H_RATIO = 0.68f
}
