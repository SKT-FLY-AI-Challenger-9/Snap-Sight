package com.example.snap_sight.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PersonFramingController] — 인물 프레이밍의 case 1(바운딩 박스 이탈)/case 2(전체 진입)
 * 분기, 3초 확정 촬영, bbox 높이 70% 이상·일반 READY 도달 시의 단축 경로를 검증한다
 * (사용자 요청 2026-08-28).
 */
class PersonFramingControllerTest {

    @Test
    fun `case1 out of frame stays silent outside the head target band`() {
        val controller = PersonFramingController()
        val below = controller.onJudgment(
            bboxFitsFrame = false, headY = 0.5f, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = 0,
        )
        assertEquals(PersonFramingController.Action.None, below.action)
        assertFalse(below.vibrate)
    }

    @Test
    fun `case1 out of frame vibrates once on entering the head target band then captures after hold`() {
        val controller = PersonFramingController()
        val entered = controller.onJudgment(
            bboxFitsFrame = false, headY = 0.20f, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = 0,
        )
        assertEquals(PersonFramingController.Action.None, entered.action)
        assertTrue(entered.vibrate)

        // 유지 중엔 다시 진동하지 않는다
        val stillHeld = controller.onJudgment(
            bboxFitsFrame = false, headY = 0.20f, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = 1_000,
        )
        assertFalse(stillHeld.vibrate)
        assertEquals(PersonFramingController.Action.None, stillHeld.action)

        val fired = controller.onJudgment(
            bboxFitsFrame = false, headY = 0.20f, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = PersonFramingController.HOLD_MS,
        )
        assertEquals(PersonFramingController.Action.Capture, fired.action)

        // 한 세션에 한 번만 — reset 전까지 다시 Capture 를 반환하지 않는다
        val again = controller.onJudgment(
            bboxFitsFrame = false, headY = 0.20f, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = PersonFramingController.HOLD_MS + 5_000,
        )
        assertEquals(PersonFramingController.Action.None, again.action)
    }

    @Test
    fun `case1 leaving the band before hold completes resets the timer`() {
        val controller = PersonFramingController()
        controller.onJudgment(
            bboxFitsFrame = false, headY = 0.20f, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = 0,
        )
        // 잠깐 벗어남 — 유지 시간 리셋
        controller.onJudgment(
            bboxFitsFrame = false, headY = 0.5f, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = 1_000,
        )
        val backAgain = controller.onJudgment(
            bboxFitsFrame = false, headY = 0.20f, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = PersonFramingController.HOLD_MS,
        )
        // 새로 진입한 것이므로 아직 HOLD_MS 를 못 채웠다 — Capture 아님
        assertEquals(PersonFramingController.Action.None, backAgain.action)
        assertTrue(backAgain.vibrate)
    }

    @Test
    fun `case2 in frame requests zoom steps and vibrates only on the first step`() {
        val controller = PersonFramingController()
        val first = controller.onJudgment(
            bboxFitsFrame = true, headY = 0.5f, footY = 0.9f, bboxHeightRatio = 0.3f,
            generalReady = false, nowMs = 0,
        )
        assertEquals(PersonFramingController.Action.RequestZoomStep, first.action)
        assertTrue(first.vibrate)

        val second = controller.onJudgment(
            bboxFitsFrame = true, headY = 0.4f, footY = 0.85f, bboxHeightRatio = 0.4f,
            generalReady = false, nowMs = 500,
        )
        assertEquals(PersonFramingController.Action.RequestZoomStep, second.action)
        assertFalse(second.vibrate)
    }

    @Test
    fun `case2 reaching both head and foot bands vibrates once then captures after hold`() {
        val controller = PersonFramingController()
        controller.onJudgment(
            bboxFitsFrame = true, headY = 0.5f, footY = 0.9f, bboxHeightRatio = 0.3f,
            generalReady = false, nowMs = 0,
        )

        val reached = controller.onJudgment(
            bboxFitsFrame = true, headY = 0.10f, footY = 0.70f, bboxHeightRatio = 0.65f,
            generalReady = false, nowMs = 800,
        )
        assertEquals(PersonFramingController.Action.None, reached.action)
        assertTrue(reached.vibrate)

        val stillHeld = controller.onJudgment(
            bboxFitsFrame = true, headY = 0.10f, footY = 0.70f, bboxHeightRatio = 0.65f,
            generalReady = false, nowMs = 1_800,
        )
        assertFalse(stillHeld.vibrate)

        val fired = controller.onJudgment(
            bboxFitsFrame = true, headY = 0.10f, footY = 0.70f, bboxHeightRatio = 0.65f,
            generalReady = false, nowMs = 800 + PersonFramingController.HOLD_MS,
        )
        assertEquals(PersonFramingController.Action.Capture, fired.action)
    }

    @Test
    fun `case2 only head in band without foot in band keeps requesting zoom`() {
        val controller = PersonFramingController()
        val outcome = controller.onJudgment(
            bboxFitsFrame = true, headY = 0.10f, footY = 0.9f, bboxHeightRatio = 0.5f,
            generalReady = false, nowMs = 0,
        )
        assertEquals(PersonFramingController.Action.RequestZoomStep, outcome.action)
    }

    @Test
    fun `null landmarks never trigger capture`() {
        val controller = PersonFramingController()
        val case1 = controller.onJudgment(
            bboxFitsFrame = false, headY = null, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = 0,
        )
        assertEquals(PersonFramingController.Action.None, case1.action)
        val case2 = controller.onJudgment(
            bboxFitsFrame = true, headY = null, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = 0,
        )
        assertEquals(PersonFramingController.Action.RequestZoomStep, case2.action)
    }

    @Test
    fun `reset clears fired state and hold progress`() {
        val controller = PersonFramingController()
        controller.onJudgment(
            bboxFitsFrame = false, headY = 0.20f, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = 0,
        )
        controller.onJudgment(
            bboxFitsFrame = false, headY = 0.20f, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = PersonFramingController.HOLD_MS,
        )
        controller.reset()
        val afterReset = controller.onJudgment(
            bboxFitsFrame = false, headY = 0.20f, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = 0,
        )
        assertTrue(afterReset.vibrate) // 처음 진입한 것처럼 다시 진동
    }

    // ---- bbox 높이 80% 이상 1초 유지 — "충분히 가까움" 단축 경로 (사용자 요청 2026-08-28) ----

    @Test
    fun `close enough height must hold for CLOSE_ENOUGH_HOLD_MS before skipping zoom`() {
        val controller = PersonFramingController()
        // 80% 넘은 첫 프레임 — 아직 유지 시간을 못 채워서 줌 요청은 계속된다
        val firstFrame = controller.onJudgment(
            bboxFitsFrame = true, headY = null, footY = null,
            bboxHeightRatio = PersonFramingController.CLOSE_ENOUGH_HEIGHT_RATIO,
            generalReady = false, nowMs = 0,
        )
        assertEquals(PersonFramingController.Action.RequestZoomStep, firstFrame.action)

        // CLOSE_ENOUGH_HOLD_MS 이상 유지되면 그제서야 목표 도달 처리(줌 요청 없음)
        val held = controller.onJudgment(
            bboxFitsFrame = true, headY = null, footY = null,
            bboxHeightRatio = PersonFramingController.CLOSE_ENOUGH_HEIGHT_RATIO,
            generalReady = false, nowMs = PersonFramingController.CLOSE_ENOUGH_HOLD_MS,
        )
        assertEquals(PersonFramingController.Action.None, held.action)
        assertTrue(held.vibrate)

        val fired = controller.onJudgment(
            bboxFitsFrame = true, headY = null, footY = null,
            bboxHeightRatio = PersonFramingController.CLOSE_ENOUGH_HEIGHT_RATIO,
            generalReady = false, nowMs = PersonFramingController.CLOSE_ENOUGH_HOLD_MS + PersonFramingController.HOLD_MS,
        )
        assertEquals(PersonFramingController.Action.Capture, fired.action)
    }

    @Test
    fun `a brief spike above the close-enough height does not count toward the hold`() {
        val controller = PersonFramingController()
        controller.onJudgment(
            bboxFitsFrame = true, headY = null, footY = null,
            bboxHeightRatio = PersonFramingController.CLOSE_ENOUGH_HEIGHT_RATIO,
            generalReady = false, nowMs = 0,
        )
        // 잠깐 임계값 아래로 떨어짐 — 유지 타이머가 리셋돼야 한다
        controller.onJudgment(
            bboxFitsFrame = true, headY = null, footY = null, bboxHeightRatio = 0.5f,
            generalReady = false, nowMs = 500,
        )
        val backAbove = controller.onJudgment(
            bboxFitsFrame = true, headY = null, footY = null,
            bboxHeightRatio = PersonFramingController.CLOSE_ENOUGH_HEIGHT_RATIO,
            generalReady = false, nowMs = PersonFramingController.CLOSE_ENOUGH_HOLD_MS,
        )
        // 500ms 지점에서 다시 시작한 것이므로 CLOSE_ENOUGH_HOLD_MS 를 아직 못 채웠다
        assertEquals(PersonFramingController.Action.RequestZoomStep, backAbove.action)
    }

    @Test
    fun `close enough height also short-circuits case1 head band wait once held`() {
        val controller = PersonFramingController()
        // case1(화면 이탈)인데 머리 좌표를 못 찾아도(극근접 포즈 실패) 높이만으로 도달 처리 —
        // 단, 여기서도 CLOSE_ENOUGH_HOLD_MS 는 채워야 한다.
        controller.onJudgment(
            bboxFitsFrame = false, headY = null, footY = null, bboxHeightRatio = 0.95f,
            generalReady = false, nowMs = 0,
        )
        val held = controller.onJudgment(
            bboxFitsFrame = false, headY = null, footY = null, bboxHeightRatio = 0.95f,
            generalReady = false, nowMs = PersonFramingController.CLOSE_ENOUGH_HOLD_MS,
        )
        assertEquals(PersonFramingController.Action.None, held.action)
        assertTrue(held.vibrate)
    }

    @Test
    fun `below the close-enough threshold still requires the normal target bands`() {
        val controller = PersonFramingController()
        val outcome = controller.onJudgment(
            bboxFitsFrame = true, headY = 0.5f, footY = 0.9f, bboxHeightRatio = 0.69f,
            generalReady = false, nowMs = 0,
        )
        assertEquals(PersonFramingController.Action.RequestZoomStep, outcome.action)
    }

    // ---- 일반 READY("좋아요") 도달 시 단축 경로 (사용자 요청 2026-08-28 —
    // "왜 좋아요만 뜨고 자동으로 촬영 안되지") ----

    @Test
    fun `general ready is ignored in case2 so zoom keeps going while still far`() {
        // 사용자 요청 2026-08-28 — "거기까지 안 가고 멀어도 지금 촬영이 자꾸 되잖아
        // 클로즈업을 안 하고". case 2 는 아직 줌 여지가 있으니 generalReady 만으로
        // 도달 처리하면 안 된다 — 정밀 밴드를 채울 때까지 계속 줌 요청해야 한다.
        val controller = PersonFramingController()
        val outcome = controller.onJudgment(
            bboxFitsFrame = true, headY = 0.5f, footY = 0.9f, bboxHeightRatio = 0.3f,
            generalReady = true, nowMs = 0,
        )
        assertEquals(PersonFramingController.Action.RequestZoomStep, outcome.action)
    }

    // ---- 줌은 정지 피사체에만 (엔드유저 피드백 2026-08-30 — "움직이는 피사체는 줌하면 놓친다") ----

    @Test
    fun `case2 withholds the zoom step while the subject is moving and resumes once static`() {
        val controller = PersonFramingController()
        val moving = controller.onJudgment(
            bboxFitsFrame = true, headY = 0.5f, footY = 0.9f, bboxHeightRatio = 0.3f,
            generalReady = false, nowMs = 0, subjectMoving = true,
        )
        assertEquals(PersonFramingController.Action.None, moving.action)
        assertFalse(moving.vibrate)

        // 멈추면 그때 첫 스텝 — 첫 실제 스텝에서 진동하는 규약 유지
        val settled = controller.onJudgment(
            bboxFitsFrame = true, headY = 0.5f, footY = 0.9f, bboxHeightRatio = 0.3f,
            generalReady = false, nowMs = 1_500, subjectMoving = false,
        )
        assertEquals(PersonFramingController.Action.RequestZoomStep, settled.action)
        assertTrue(settled.vibrate)

        // 다시 움직이면 다시 보류 — 이미 시작한 줌을 되돌리지는 않는다(액션 없음)
        val movingAgain = controller.onJudgment(
            bboxFitsFrame = true, headY = 0.4f, footY = 0.85f, bboxHeightRatio = 0.4f,
            generalReady = false, nowMs = 2_000, subjectMoving = true,
        )
        assertEquals(PersonFramingController.Action.None, movingAgain.action)
    }

    @Test
    fun `a moving subject can still reach the target bands and capture without zoom`() {
        val controller = PersonFramingController()
        val reached = controller.onJudgment(
            bboxFitsFrame = true, headY = 0.10f, footY = 0.70f, bboxHeightRatio = 0.65f,
            generalReady = false, nowMs = 0, subjectMoving = true,
        )
        assertEquals(PersonFramingController.Action.None, reached.action)
        assertTrue(reached.vibrate)
        val fired = controller.onJudgment(
            bboxFitsFrame = true, headY = 0.10f, footY = 0.70f, bboxHeightRatio = 0.65f,
            generalReady = false, nowMs = PersonFramingController.HOLD_MS, subjectMoving = true,
        )
        assertEquals(PersonFramingController.Action.Capture, fired.action)
    }

    @Test
    fun `subject motion does not change case1 behaviour`() {
        val controller = PersonFramingController()
        val outcome = controller.onJudgment(
            bboxFitsFrame = false, headY = 0.20f, footY = null, bboxHeightRatio = null,
            generalReady = false, nowMs = 0, subjectMoving = true,
        )
        assertEquals(PersonFramingController.Action.None, outcome.action)
        assertTrue(outcome.vibrate)
    }

    @Test
    fun `general ready short-circuits case1 head band wait too`() {
        val controller = PersonFramingController()
        val outcome = controller.onJudgment(
            bboxFitsFrame = false, headY = null, footY = null, bboxHeightRatio = null,
            generalReady = true, nowMs = 0,
        )
        assertEquals(PersonFramingController.Action.None, outcome.action)
        assertTrue(outcome.vibrate)
    }
}
