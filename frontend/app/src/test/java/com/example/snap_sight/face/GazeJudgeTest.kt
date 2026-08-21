package com.example.snap_sight.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 셀카 시선 판정(순수 로직) — 머리 각도 → 눈 뜸 → 동공 위치 3단 규칙. */
class GazeJudgeTest {

    @Test
    fun facingCameraWithOpenEyesAndCenteredPupilIsLooking() {
        assertEquals(
            GazeJudge.Verdict.LOOKING,
            GazeJudge.judge(0f, 0f, 0.9f, 0.9f, pupilHorizontalRatio = 0.5f),
        )
        // 임계값 안의 약간의 기울임·약간의 시선 치우침은 허용
        assertEquals(
            GazeJudge.Verdict.LOOKING,
            GazeJudge.judge(10f, -8f, 0.8f, 0.7f, pupilHorizontalRatio = 0.4f),
        )
    }

    @Test
    fun turnedHeadWinsOverEverythingElse() {
        assertEquals(GazeJudge.Verdict.HEAD_TURNED, GazeJudge.judge(30f, 0f, 0.9f, 0.9f, 0.5f))
        assertEquals(GazeJudge.Verdict.HEAD_TURNED, GazeJudge.judge(0f, -25f, 0.9f, 0.9f, 0.5f))
    }

    @Test
    fun closedEyesAreDetected() {
        assertEquals(GazeJudge.Verdict.EYES_CLOSED, GazeJudge.judge(0f, 0f, 0.05f, 0.1f, null))
    }

    @Test
    fun frontalHeadWithSidewaysPupilIsEyesAway() {
        // 핵심 시나리오 (2026-08-21 피드백): 얼굴은 정면, 눈동자만 옆을 봄
        assertEquals(
            GazeJudge.Verdict.EYES_AWAY,
            GazeJudge.judge(0f, 0f, 0.9f, 0.9f, pupilHorizontalRatio = 0.15f),
        )
        assertEquals(
            GazeJudge.Verdict.EYES_AWAY,
            GazeJudge.judge(0f, 0f, 0.9f, 0.9f, pupilHorizontalRatio = 0.85f),
        )
    }

    @Test
    fun missingPupilFallsBackToHeadPoseOnly() {
        // 동공을 못 찾음(안경 반사·저조도) — 머리 방향·눈 뜸만으로 판정 (fail-open)
        assertEquals(GazeJudge.Verdict.LOOKING, GazeJudge.judge(0f, 0f, 0.9f, 0.9f, null))
    }

    @Test
    fun oneOpenEyeCountsAsOpen() {
        assertEquals(GazeJudge.Verdict.LOOKING, GazeJudge.judge(0f, 0f, 0.05f, 0.9f, 0.5f))
        assertEquals(GazeJudge.Verdict.LOOKING, GazeJudge.judge(0f, 0f, null, 0.9f, 0.5f))
    }

    @Test
    fun missingEyeProbabilitiesFallBackToAnglesAndPupil() {
        // 분류 미지원 기기 — 각도·동공만으로 판정한다
        assertTrue(GazeJudge.isLookingAtCamera(5f, 5f, null, null))
        assertFalse(GazeJudge.isLookingAtCamera(30f, 0f, null, null))
        assertEquals(GazeJudge.Verdict.EYES_AWAY, GazeJudge.judge(5f, 5f, null, null, 0.1f))
    }
}
