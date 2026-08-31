package com.example.snap_sight.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DocumentGuide] — 서류 모드(2026-08-30)의 단계별 안내(탐색→잘림→크기(줌)→위치→회전→반사),
 * 안내 뒤 정착 시간, "좋아요" 발화 뒤 정지 유지 자동촬영, 히스테리시스, 문구 반복 게이팅을 검증한다.
 */
class DocumentGuideTest {

    private fun obs(
        left: Float = 0.15f,
        top: Float = 0.15f,
        right: Float = 0.85f,
        bottom: Float = 0.85f,
        lines: Int = 6,
        angle: Float = 0f,
        gradient: Float = 0f,
        glare: Float = 0f,
        at: Long = 0L,
        corners: DocumentQuad? = null,
        edgeLeft: Boolean = false,
        edgeTop: Boolean = false,
        edgeRight: Boolean = false,
        edgeBottom: Boolean = false,
    ) = DocumentObservation(
        left = left, top = top, right = right, bottom = bottom,
        lineCount = lines, angleDegrees = angle, heightGradient = gradient,
        glareFraction = glare, atMs = at, corners = corners,
        edgeLeft = edgeLeft, edgeTop = edgeTop, edgeRight = edgeRight, edgeBottom = edgeBottom,
    )

    /** 위/아래 변 폭을 지정한 사다리꼴 (중앙 정렬, 높이 0.6·세로 0.2~0.8). */
    private fun trapezoid(topWidth: Float, bottomWidth: Float, leftHeight: Float = 0.6f, rightHeight: Float = 0.6f): DocumentQuad {
        val topHalf = topWidth / 2f
        val bottomHalf = bottomWidth / 2f
        val topLeftY = 0.5f - leftHeight / 2f
        val topRightY = 0.5f - rightHeight / 2f
        return DocumentQuad(
            tl = DocPoint(0.5f - topHalf, topLeftY),
            tr = DocPoint(0.5f + topHalf, topRightY),
            br = DocPoint(0.5f + bottomHalf, topRightY + rightHeight),
            bl = DocPoint(0.5f - bottomHalf, topLeftY + leftHeight),
        )
    }

    private val small = obs(left = 0.35f, top = 0.35f, right = 0.65f, bottom = 0.65f) // 면적 9%

    @Test
    fun `no document speaks the search hint only after the delay`() {
        val guide = DocumentGuide()
        val first = guide.onJudgment(null, subjectStatic = true, nowMs = 0)
        assertNull(first.utterance)
        assertEquals(DocumentGuide.STATUS_SEARCHING, first.statusText)
        val hinted = guide.onJudgment(null, subjectStatic = true, nowMs = DocumentGuide.SEARCH_HINT_AFTER_MS)
        assertEquals(DocumentGuide.SEARCHING_UTTERANCE, hinted.utterance)
    }

    @Test
    fun `stale or single-line observations count as not found`() {
        val guide = DocumentGuide()
        val stale = guide.onJudgment(obs(at = 0), subjectStatic = true, nowMs = DocumentGuide.FRESH_MS + 1)
        assertEquals(DocumentGuide.STATUS_SEARCHING, stale.statusText)
        val oneLine = guide.onJudgment(obs(lines = 1, at = 100), subjectStatic = true, nowMs = 100)
        assertEquals(DocumentGuide.STATUS_SEARCHING, oneLine.statusText)
    }

    @Test
    fun `text touching both sides zooms out when possible otherwise asks to move away`() {
        val zoomOut = DocumentGuide().onJudgment(obs(left = 0f, right = 1f), true, 0, zoomOutAvailable = true)
        assertEquals(DocumentGuide.Zoom.OUT, zoomOut.zoom)
        assertNull(zoomOut.utterance)
        assertEquals(DocumentGuide.STATUS_ZOOMING_OUT, zoomOut.statusText)

        val bothSides = DocumentGuide().onJudgment(obs(left = 0f, right = 1f), true, 0)
        assertEquals(DocumentGuide.FARTHER_UTTERANCE, bothSides.utterance)
        assertEquals(DocumentGuide.STATUS_TOO_CLOSE, bothSides.statusText)

        val leftOnly = DocumentGuide().onJudgment(obs(left = 0.005f), true, 0)
        assertEquals(DocumentGuide.SHIFT_LEFT_UTTERANCE, leftOnly.utterance)
        val bottomOnly = DocumentGuide().onJudgment(obs(bottom = 0.995f), true, 0)
        assertEquals(DocumentGuide.SHIFT_DOWN_UTTERANCE, bottomOnly.utterance)
    }

    @Test
    fun `clipping margin is wider on entry and narrower while holding`() {
        // 진입: 여백 2.5%(< 3%)면 잘림으로 본다 (2026-08-31 — 빠듯한 여백이 원근 보정을 놓치게 했다)
        val entering = DocumentGuide().onJudgment(obs(left = 0.025f), true, 0)
        assertEquals(DocumentGuide.SHIFT_LEFT_UTTERANCE, entering.utterance)
        // 통과 상태에서는 1.5%까지 버틴다 (히스테리시스)
        val guide = DocumentGuide()
        guide.onJudgment(obs(at = 0), true, 0) // READY
        val kept = guide.onJudgment(obs(left = 0.02f, at = 500), true, 500)
        assertNull(kept.utterance)
        assertEquals(DocumentGuide.STATUS_HOLD, kept.statusText)
    }

    @Test
    fun `a single missing edge with a tight margin asks to shift toward it`() {
        // 실기기 2026-08-31: 윗변만 미검출 + 위 여백 빠듯 → 프레임 밖으로 나간 것 — 위로 옮기라고 한다
        val outcome = DocumentGuide().onJudgment(
            obs(top = 0.05f, edgeLeft = true, edgeRight = true, edgeBottom = true), true, 0,
        )
        assertEquals(DocumentGuide.SHIFT_UP_UTTERANCE, outcome.utterance)
        assertEquals(DocumentGuide.STATUS_CLIPPED, outcome.statusText)
        // 여백이 넉넉하면(8% 이상) 대비 없는 변으로 보고 통과한다 (fail-open)
        val roomy = DocumentGuide().onJudgment(
            obs(top = 0.12f, edgeLeft = true, edgeRight = true, edgeBottom = true), true, 0,
        )
        assertEquals(DocumentGuide.READY_UTTERANCE, roomy.utterance)
        // 두 변 이하로 잡힌 경우는 이 규칙을 적용하지 않는다
        val fewEdges = DocumentGuide().onJudgment(
            obs(top = 0.05f, edgeLeft = true, edgeBottom = true), true, 0,
        )
        assertEquals(DocumentGuide.READY_UTTERANCE, fewEdges.utterance)
    }

    @Test
    fun `small text area zooms in silently while zoom is available`() {
        val outcome = DocumentGuide().onJudgment(small, true, 0, zoomInAvailable = true)
        assertEquals(DocumentGuide.Zoom.IN, outcome.zoom)
        assertNull(outcome.utterance)
        assertEquals(DocumentGuide.STATUS_ZOOMING_IN, outcome.statusText)
        // 목표(40%) 미만이면 최소(30%)를 넘어도 계속 확대한다
        val mid = obs(left = 0.2f, top = 0.2f, right = 0.8f, bottom = 0.8f) // 36%
        assertEquals(DocumentGuide.Zoom.IN, DocumentGuide().onJudgment(mid, true, 0, zoomInAvailable = true).zoom)
    }

    @Test
    fun `zoom in is blocked when a step would push the document edge out`() {
        // 글자 상자가 가로로 이미 넓다 — 면적(18%)은 목표 미만이지만 한 스텝 뒤 좌우가 안전
        // 여백을 뚫는다. 줌 대신 "가까이"로 푼다 (과확대 엣지 잘림 방지, 2026-08-31).
        val wideThin = obs(left = 0.05f, right = 0.95f, top = 0.4f, bottom = 0.6f)
        val outcome = DocumentGuide().onJudgment(wideThin, true, 0, zoomInAvailable = true)
        assertNull(outcome.zoom)
        assertEquals(DocumentGuide.CLOSER_UTTERANCE, outcome.utterance)
    }

    @Test
    fun `zoom guard uses corners when available and text box otherwise`() {
        val guide = DocumentGuide()
        // 모서리 4점 기준: 중앙 0.5×0.6 사각형 — 극값 0.3, 스텝 후 0.324 ≤ 0.44 → 허용
        val fits = obs(
            left = 0.3f, right = 0.7f, top = 0.3f, bottom = 0.7f,
            corners = trapezoid(topWidth = 0.5f, bottomWidth = 0.5f),
        )
        assertTrue(guide.zoomStepKeepsDocumentInside(fits))
        // 모서리가 이미 가장자리 근처 (극값 0.42, 스텝 후 0.4536 > 0.44) → 차단
        val nearEdge = obs(
            left = 0.15f, right = 0.85f, top = 0.35f, bottom = 0.65f,
            corners = trapezoid(topWidth = 0.84f, bottomWidth = 0.84f, leftHeight = 0.72f, rightHeight = 0.72f),
        )
        assertFalse(guide.zoomStepKeepsDocumentInside(nearEdge))
        // 모서리가 없으면 글자 상자 + 더 큰 안전 여백 — 극값 0.38, 스텝 후 0.41 > 0.38 → 차단
        assertFalse(guide.zoomStepKeepsDocumentInside(obs(left = 0.12f, right = 0.88f, top = 0.3f, bottom = 0.7f)))
    }

    @Test
    fun `small text area asks to come closer only when zoom is exhausted`() {
        val outcome = DocumentGuide().onJudgment(small, true, 0, zoomInAvailable = false)
        assertEquals(DocumentGuide.CLOSER_UTTERANCE, outcome.utterance)
        assertNull(outcome.zoom)
        assertEquals(DocumentGuide.STATUS_TOO_FAR, outcome.statusText)
        // 줌을 다 썼고 30% 이상이면 크기는 통과다
        val mid = obs(left = 0.2f, top = 0.2f, right = 0.8f, bottom = 0.8f)
        assertEquals(DocumentGuide.READY_UTTERANCE, DocumentGuide().onJudgment(mid, true, 0).utterance)
    }

    @Test
    fun `off-center area asks to shift toward the document along the worse axis`() {
        // 서류가 오른쪽·약간 아래에 있음 — 더 벗어난 좌우 축만 말한다
        val outcome = DocumentGuide().onJudgment(
            obs(left = 0.30f, top = 0.20f, right = 0.96f, bottom = 0.90f), true, 0,
        )
        assertEquals(DocumentGuide.SHIFT_RIGHT_UTTERANCE, outcome.utterance)
        assertEquals(DocumentGuide.STATUS_OFF_CENTER, outcome.statusText)
    }

    @Test
    fun `height gradient alone never triggers tilt guidance`() {
        // 제목·본문 글자 크기 차이를 기울기로 오인하던 문제 (실기기 2026-08-30) — 기울임은
        // 모서리 수렴비로만 판정한다
        val outcome = DocumentGuide().onJudgment(obs(gradient = 0.6f), true, 0)
        assertEquals(DocumentGuide.READY_UTTERANCE, outcome.utterance)
    }

    // ---- 기울임(원근) — 모서리 수렴비 (외곽 v2, 2026-08-31) ----

    @Test
    fun `converging widths ask to tilt the top or bottom of the phone`() {
        // 윗변이 짧다(0.5/0.6 ≈ 0.83 < 0.85) = 위가 멀다 → 윗부분을 서류 쪽으로
        val topFar = DocumentGuide().onJudgment(obs(corners = trapezoid(0.5f, 0.6f)), true, 0)
        assertEquals(DocumentGuide.TILT_TOP_TOWARD_UTTERANCE, topFar.utterance)
        assertEquals(DocumentGuide.STATUS_TILTED, topFar.statusText)
        // 아랫변이 짧으면 반대
        val bottomFar = DocumentGuide().onJudgment(obs(corners = trapezoid(0.6f, 0.5f)), true, 0)
        assertEquals(DocumentGuide.TILT_TOP_AWAY_UTTERANCE, bottomFar.utterance)
    }

    @Test
    fun `converging heights ask to tilt the left or right of the phone`() {
        val leftFar = DocumentGuide().onJudgment(
            obs(corners = trapezoid(0.6f, 0.6f, leftHeight = 0.5f, rightHeight = 0.62f)), true, 0,
        )
        assertEquals(DocumentGuide.TILT_LEFT_TOWARD_UTTERANCE, leftFar.utterance)
        val rightFar = DocumentGuide().onJudgment(
            obs(corners = trapezoid(0.6f, 0.6f, leftHeight = 0.62f, rightHeight = 0.5f)), true, 0,
        )
        assertEquals(DocumentGuide.TILT_RIGHT_TOWARD_UTTERANCE, rightFar.utterance)
    }

    @Test
    fun `a nearly rectangular quad passes and missing corners skip the tilt check`() {
        // 수렴비 0.97 — 허용(0.85) 안 → READY
        val square = DocumentGuide().onJudgment(obs(corners = trapezoid(0.58f, 0.6f)), true, 0)
        assertEquals(DocumentGuide.READY_UTTERANCE, square.utterance)
        // 모서리가 없으면 기울임 판정 자체를 건너뛴다 (fail-open)
        val noQuad = DocumentGuide().onJudgment(obs(), true, 0)
        assertEquals(DocumentGuide.READY_UTTERANCE, noQuad.utterance)
    }

    @Test
    fun `rotated text lines ask to turn the phone and landscape text counts as level`() {
        val rotated = DocumentGuide().onJudgment(obs(angle = 12f), true, 0)
        val expected = if (DocumentGuide.ROTATION_SIGN > 0f) {
            GuidanceDirection.ROLL_TURN_LEFT.utterance
        } else {
            GuidanceDirection.ROLL_TURN_RIGHT.utterance
        }
        assertEquals(expected, rotated.utterance)
        assertEquals(DocumentGuide.STATUS_ROTATED, rotated.statusText)
        // 가로 서류(글자가 90° 근처)는 반듯한 것으로 본다 — 스냅 편차
        val landscape = DocumentGuide().onJudgment(obs(angle = 88f), true, 0)
        assertEquals(DocumentGuide.READY_UTTERANCE, landscape.utterance)
    }

    @Test
    fun `glare blocks capture after everything else passes`() {
        val outcome = DocumentGuide().onJudgment(obs(glare = 0.2f), true, 0)
        assertEquals(DocumentGuide.GLARE_UTTERANCE, outcome.utterance)
        assertFalse(outcome.capture)
    }

    @Test
    fun `all good announces once with vibration then captures after the hold`() {
        val guide = DocumentGuide()
        val ready = guide.onJudgment(obs(at = 0), true, 0)
        assertEquals(DocumentGuide.READY_UTTERANCE, ready.utterance)
        assertTrue(ready.vibrate)
        assertFalse(ready.capture)

        val holding = guide.onJudgment(obs(at = 500), true, 500)
        assertNull(holding.utterance) // 준비 안내는 1회
        assertFalse(holding.vibrate)
        assertEquals(DocumentGuide.STATUS_HOLD, holding.statusText)

        val fired = guide.onJudgment(obs(at = DocumentGuide.HOLD_MS), true, DocumentGuide.HOLD_MS)
        assertTrue(fired.capture)
        assertTrue(fired.vibrate)
        assertEquals(DocumentGuide.STATUS_DONE, fired.statusText)

        // 세션당 1회
        val later = DocumentGuide.HOLD_MS + 5_000
        assertFalse(guide.onJudgment(obs(at = later), true, later).capture)
    }

    @Test
    fun `ready waits for the settle time after a spoken instruction`() {
        // 실기기 2026-08-30: "기울여 주세요"가 재생 중인데 셔터가 터졌다 — 안내 뒤 정착 시간
        val guide = DocumentGuide()
        assertEquals(DocumentGuide.CLOSER_UTTERANCE, guide.onJudgment(small.copy(atMs = 0), true, 0).utterance)
        // 바로 통과해도 정착 시간 전에는 "좋아요"도, 유지 시간도 없다
        val tooSoon = guide.onJudgment(obs(at = 1_000), true, 1_000)
        assertNull(tooSoon.utterance)
        assertFalse(tooSoon.capture)
        val settleEnd = DocumentGuide.SETTLE_AFTER_INSTRUCTION_MS
        val ready = guide.onJudgment(obs(at = settleEnd), true, settleEnd)
        assertEquals(DocumentGuide.READY_UTTERANCE, ready.utterance)
        // 유지 시간은 "좋아요" 시점부터 센다 — 그 전엔 찍지 않는다
        assertFalse(guide.onJudgment(obs(at = settleEnd + DocumentGuide.HOLD_MS - 1), true, settleEnd + DocumentGuide.HOLD_MS - 1).capture)
        assertTrue(guide.onJudgment(obs(at = settleEnd + DocumentGuide.HOLD_MS), true, settleEnd + DocumentGuide.HOLD_MS).capture)
    }

    @Test
    fun `movement resets the hold and a problem resets the ready announcement`() {
        val guide = DocumentGuide()
        guide.onJudgment(obs(at = 0), true, 0)
        guide.onJudgment(obs(at = 1_000), false, 1_000) // 손에 든 신분증이 흔들림
        val notYet = guide.onJudgment(obs(at = 2_600), true, 2_600)
        assertFalse(notYet.capture) // 2600ms 에 정지가 다시 시작됐으니 유지 시간 미달

        // 문제가 생겼다 돌아오면(정착 시간 뒤) 준비 안내를 다시 한다
        guide.onJudgment(obs(left = 0f, right = 1f, at = 3_000), true, 3_000)
        val readyAgain = guide.onJudgment(obs(at = 6_000), true, 6_000)
        assertEquals(DocumentGuide.READY_UTTERANCE, readyAgain.utterance)
    }

    @Test
    fun `relaxed thresholds keep the good state near the boundary`() {
        val guide = DocumentGuide()
        guide.onJudgment(obs(at = 0), true, 0) // 통과 → "좋아요"
        // 중심이 13% 벗어남 — 진입 허용치(12%)는 넘지만 완화 허용치(18%)는 안 넘는다 → 유지
        val drift = obs(left = 0.29f, top = 0.15f, right = 0.97f, bottom = 0.85f, at = 500) // 중심 x 0.63
        val kept = guide.onJudgment(drift, true, 500)
        assertNull(kept.utterance)
        assertEquals(DocumentGuide.STATUS_HOLD, kept.statusText)
        // 처음부터 14% 벗어나 있었으면 옮기라고 한다
        assertEquals(DocumentGuide.SHIFT_RIGHT_UTTERANCE, DocumentGuide().onJudgment(drift, true, 500).utterance)
    }

    @Test
    fun `same instruction repeats only after the repeat interval`() {
        val guide = DocumentGuide()
        assertEquals(DocumentGuide.CLOSER_UTTERANCE, guide.onJudgment(small.copy(atMs = 0), true, 0).utterance)
        assertNull(guide.onJudgment(small.copy(atMs = 1_000), true, 1_000).utterance)
        assertEquals(
            DocumentGuide.CLOSER_UTTERANCE,
            guide.onJudgment(small.copy(atMs = DocumentGuide.REPEAT_MS), true, DocumentGuide.REPEAT_MS).utterance,
        )
        // 다른 문구는 최소 간격 뒤 바로
        val at = DocumentGuide.REPEAT_MS + DocumentGuide.MIN_GAP_MS
        assertEquals(DocumentGuide.SHIFT_LEFT_UTTERANCE, guide.onJudgment(obs(left = 0f, at = at), true, at).utterance)
    }

    @Test
    fun `reset allows a new capture`() {
        val guide = DocumentGuide()
        guide.onJudgment(obs(at = 0), true, 0)
        assertTrue(guide.onJudgment(obs(at = DocumentGuide.HOLD_MS), true, DocumentGuide.HOLD_MS).capture)
        guide.reset()
        val ready = guide.onJudgment(obs(at = 0), true, 0)
        assertEquals(DocumentGuide.READY_UTTERANCE, ready.utterance)
        assertTrue(guide.onJudgment(obs(at = DocumentGuide.HOLD_MS), true, DocumentGuide.HOLD_MS).capture)
    }
}
