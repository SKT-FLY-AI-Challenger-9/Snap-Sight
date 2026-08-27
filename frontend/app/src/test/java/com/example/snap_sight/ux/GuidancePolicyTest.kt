package com.example.snap_sight.ux

import com.example.snap_sight.cv.DeviationResult
import com.example.snap_sight.cv.FrameVisibility
import com.example.snap_sight.cv.ObservationFreshness
import com.example.snap_sight.cv.ReadinessBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GuidancePolicy] — "언제 무엇을 재생할지"만 검증한다 (Android 의존성 없음).
 * 2026-08-19 실사용 피드백의 핵심 계약: 방향 음성은 한 번에 하나·쿨다운, 진동은 방향 음성과 함께만,
 * LOST 는 디바운스 뒤 경고음(음성은 오래 지속될 때 1회), READY 는 안정화 뒤 1회.
 */
class GuidancePolicyTest {

    private fun result(
        x: Float,
        size: Float,
        y: Float? = 0f,
        freshness: ObservationFreshness = ObservationFreshness.FRESH,
        ageMs: Long = 0L,
        visibility: FrameVisibility? = null,
    ) = DeviationResult(
        subjectDetected = true,
        xDeviation = x,
        sizeDeviation = size,
        yDeviation = y,
        observationFreshness = freshness,
        observationAgeMs = ageMs,
        frameVisibility = visibility,
    )

    private val lostResult = DeviationResult(subjectDetected = false, xDeviation = null, sizeDeviation = null)

    private fun GuidancePolicy.feed(r: DeviationResult, now: Long) =
        onJudgment(GuidanceStateMapper.from(r), r, now)

    private fun speech(actions: List<GuidanceAction>) =
        actions.filterIsInstance<GuidanceAction.Speak>().map { it.text }

    // ---- 방향 안내 ----

    @Test
    fun `off-center speaks one direction word with a vibration`() {
        val policy = GuidancePolicy()
        val actions = policy.feed(result(x = -0.30f, size = 0f), now = 0)
        assertEquals(listOf(GuidanceAction.Speak(GuidanceDirection.Clock(11).utterance), GuidanceAction.Vibrate), actions)
    }

    @Test
    fun `same direction repeats only after the repeat interval and vibrates only then`() {
        val policy = GuidancePolicy()
        policy.feed(result(x = 0.30f, size = 0f), now = 0)
        assertTrue(policy.feed(result(x = 0.30f, size = 0f), now = 500).isEmpty())
        assertTrue(nonPresence(policy.feed(result(x = 0.30f, size = 0f), now = 2_000)).isEmpty())
        val again = policy.feed(result(x = 0.30f, size = 0f), now = GuidancePolicy.DIRECTION_REPEAT_MS)
        assertEquals(listOf(GuidanceDirection.Clock(1).utterance), speech(again))
        assertTrue(again.contains(GuidanceAction.Vibrate))
    }

    @Test
    fun `changed direction is spoken sooner but not faster than the minimum gap`() {
        val policy = GuidancePolicy()
        policy.feed(result(x = 0.30f, size = 0f), now = 0)
        assertTrue(policy.feed(result(x = 0f, size = -0.30f), now = 400).isEmpty())
        assertEquals(listOf(GuidanceDirection.CLOSER.utterance), speech(policy.feed(result(x = 0f, size = -0.30f), now = GuidancePolicy.DIRECTION_MIN_GAP_MS)))
    }

    @Test
    fun `the axis furthest past its threshold wins`() {
        // x: 0.25/0.20 = 1.25, size: +0.30/0.10 = 3.0 이지만 FARTHER("뒤로")는 안내하지 않으므로 x 가 뽑힌다
        val actions = GuidancePolicy().feed(result(x = 0.25f, size = 0.30f), now = 0)
        assertEquals(listOf(GuidanceDirection.Clock(1).utterance), speech(actions))
        // x·y 둘 다 벗어나 있어도 절대 섞지 않는다(사용자 요청 2026-08-27) — 더 급한 쪽(y,
        // 2.0 > x의 1.25) 하나만 본다. y=-0.50은 무게중심이 위쪽 칸 바깥쪽 절반까지 가서
        // 급한 구역이라 "몸쪽으로 기울여 주세요"가 된다.
        val vertical = GuidancePolicy().feed(result(x = 0.25f, size = 0f, y = -0.50f), now = 0)
        assertEquals(listOf(GuidanceDirection.TILT_TOP_TOWARD.utterance), speech(vertical))
        val down = GuidancePolicy().feed(result(x = 0f, size = 0f, y = 0.40f), now = 0)
        assertEquals(listOf(GuidanceDirection.TILT_TOP_AWAY.utterance), speech(down))
    }

    @Test
    fun `dominant axis wins without blending into a diagonal hour`() {
        // 사용자 요청 2026-08-27 — 좌우·상하를 절대 섞지 않는다("8시·5시·7시 이런 게 나오면
        // 안 돼"). 둘 다 벗어나 있어도 더 급한 쪽 하나만 순수하게 말한다.
        val horizontalWins = GuidancePolicy().feed(result(x = 0.30f, size = 0f, y = -0.26f), now = 0)
        assertEquals(listOf(GuidanceDirection.Clock(1).utterance), speech(horizontalWins))
        val verticalWins = GuidancePolicy().feed(result(x = 0.21f, size = 0f, y = -0.50f), now = 0)
        assertEquals(listOf(GuidanceDirection.TILT_TOP_TOWARD.utterance), speech(verticalWins))
    }

    @Test
    fun `vertical zone escalates from clock hour to tilt wording based on centroid position`() {
        // 사용자 요청 2026-08-27 — 여백(margin) 대신 무게중심 위치로 판정한다(물체 크기가
        // 다르면 여백 비율이 달라져 들쭉날쭉했음). 위쪽 칸(키패드 2번)의 중심 쪽 절반까지
        // 왔으면 완만하게 12시, 바깥쪽 절반이면 "몸쪽으로 기울여 주세요".
        val mildUp = result(x = 0f, size = 0f, y = -0.28f) // centerY=0.22, 안쪽 절반[1/6,1/3)
        assertEquals(listOf(GuidanceDirection.Clock(12).utterance), speech(GuidancePolicy().feed(mildUp, now = 0)))

        val severeUp = result(x = 0f, size = 0f, y = -0.50f) // centerY=0.0, 바깥쪽 절반
        assertEquals(
            listOf(GuidanceDirection.TILT_TOP_TOWARD.utterance),
            speech(GuidancePolicy().feed(severeUp, now = 0)),
        )

        val severeDown = result(x = 0f, size = 0f, y = 0.40f) // centerY=0.90, 바깥쪽 절반
        assertEquals(
            listOf(GuidanceDirection.TILT_TOP_AWAY.utterance),
            speech(GuidancePolicy().feed(severeDown, now = 0)),
        )
    }

    @Test
    fun `horizontal zone escalates from mild to severe based on centroid position`() {
        // 사용자 요청 2026-08-27 — 왼쪽(키패드 4번)·오른쪽(6번) 칸도 무게중심이 중심 쪽 절반
        // 까지 왔으면 완만한 11시·1시, 아직 바깥쪽 절반이면 더 급한 10시·2시.
        val farFromEdge = result(x = 0.30f, size = 0f) // centerX=0.80, 안쪽 절반[1/3,5/6)
        assertEquals(listOf(GuidanceDirection.Clock(1).utterance), speech(GuidancePolicy().feed(farFromEdge, now = 0)))

        val nearEdge = result(x = 0.35f, size = 0f) // centerX=0.85, 바깥쪽 절반
        assertEquals(listOf(GuidanceDirection.Clock(2).utterance), speech(GuidancePolicy().feed(nearEdge, now = 0)))
    }

    @Test
    fun `visible subject direction never exceeds camera half field of view`() {
        // 사용자 확인 2026-08-27 — "정면=12시, 화면 안에서는 카메라 반화각(~33도)만큼만
        // 벗어나니까 11시~1시 근처만 나온다. 3시·9시가 실제로 나오려면 화면 밖으로 완전히
        // 벗어난(LOST) 상황이어야 한다." 화면에 보이는 동안은 자이로를 줘도 무시하고 항상
        // 이 좁은 범위 안에서만 말한다 — [pickDirection]은 cameraOrientationRad를 아예 받지
        // 않는다(사용 안 함). 대신 [onJudgment]에 자이로를 줘도(피사체가 보이는 동안은) 결과가
        // 안 바뀌는지 확인한다.
        val visible = result(x = 0.30f, size = 0f)
        val withoutGyro = speech(GuidancePolicy().feed(visible, now = 0))
        val withGyro = speech(
            GuidancePolicy().onJudgment(
                GuidanceStateMapper.from(visible), visible, nowMs = 0,
                cameraOrientationRad = -1.3963f to 0f, // 80도 돌아간 척해도 무시돼야 함
            ),
        )
        assertEquals(listOf(GuidanceDirection.Clock(1).utterance), withoutGyro)
        assertEquals(withoutGyro, withGyro)
    }

    @Test
    fun `lost subject search direction tracks last known bearing plus turn since loss`() {
        // 사용자 확인 2026-08-27 — 화면에서 완전히 벗어나면(LOST) 마지막으로 보였던 방향에
        // 그 이후 실제로 돈 양(자이로)을 반영해, 화면 안에서는 못 나오는 먼 시각(11시 등)까지
        // 안내해야 한다. subjectDesignated를 안 줘서 "사라졌어요" 안내와 안 겹치게 한다.
        val policy = GuidancePolicy()
        val visible = result(x = 0.30f, size = 0f)
        val firstActions = policy.onJudgment(
            GuidanceStateMapper.from(visible), visible, nowMs = 0, cameraOrientationRad = 0f to 0f,
        )
        assertEquals(listOf(GuidanceDirection.Clock(1).utterance), speech(firstActions))

        // 놓침 — 디바운스 전에는 침묵 (기존 LOST 규칙 그대로)
        assertTrue(
            policy.onJudgment(
                GuidanceStateMapper.from(lostResult), lostResult,
                nowMs = 850, cameraOrientationRad = 0f to 0f,
            ).isEmpty(),
        )
        // 디바운스 뒤: 완전히 놓친 순간은 오른쪽으로 사라졌으니 곧장 3시부터 시작한다
        // (사용자 요청 2026-08-27 — "9시 영역에서 아예 사라지면 9시", 반대쪽은 3시).
        val justLost = policy.onJudgment(
            GuidanceStateMapper.from(lostResult), lostResult,
            nowMs = 850 + GuidancePolicy.LOST_DEBOUNCE_MS + 1, cameraOrientationRad = 0f to 0f,
        )
        assertEquals(listOf(GuidanceDirection.Clock(3).utterance), speech(justLost))

        // 그 사이 오른쪽으로 120도나 돌아 목표(90도)를 지나쳐버림 — 되돌아가야 하니 11시로 갱신
        val overTurned = policy.onJudgment(
            GuidanceStateMapper.from(lostResult), lostResult,
            nowMs = 850 + GuidancePolicy.LOST_DEBOUNCE_MS + 1 + GuidancePolicy.DIRECTION_REPEAT_MS,
            cameraOrientationRad = -2.0944f to 0f, // 오른쪽으로 120도 돎(90도를 넘어 지나침)
        )
        assertEquals(listOf(GuidanceDirection.Clock(11).utterance), speech(overTurned))
    }

    @Test
    fun `vertical-only deviation is spoken instead of READY`() {
        // x·size 는 CENTERED(계약상 isReady) 지만 위로 벗어남 → "촬영하세요" 대신 GuidanceDirection.Clock(12).utterance
        val policy = GuidancePolicy()
        assertEquals(listOf(GuidanceDirection.Clock(12).utterance), speech(policy.feed(result(x = 0f, size = 0f, y = -0.30f), now = 0)))
        assertTrue(policy.feed(result(x = 0f, size = 0f, y = -0.30f), now = 300).isEmpty())
    }

    @Test
    fun `vertical within tolerance participates in READY`() {
        // x·size·y 모두 허용 범위 → READY
        val policy = GuidancePolicy()
        policy.feed(result(x = 0f, size = 0f, y = 0.10f), now = 0)
        val actions = policy.feed(result(x = 0f, size = 0f, y = 0.10f), now = GuidancePolicy.READY_DEBOUNCE_MS)
        assertEquals(listOf(GuidancePolicy.READY_UTTERANCE), speech(actions))
    }

    @Test
    fun `too small is left to auto zoom while zoom has headroom`() {
        val policy = GuidancePolicy()
        val r = result(x = 0f, size = -0.30f)
        // 줌 여유 있음 → GuidanceDirection.CLOSER.utterance는 말하지 않는다 (READY 도 아님). 다만 무한 침묵 대신
        // 4초 뒤 하트비트로 "자동으로 맞추는 중"임을 알린다 (2026-08-23 죽은 공백 방지)
        assertTrue(policy.onJudgment(GuidanceStateMapper.from(r), r, 0, zoomHandlesDistance = true).isEmpty())
        assertEquals(
            listOf(GuidancePolicy.AUTO_ZOOM_HEARTBEAT),
            speech(policy.onJudgment(GuidanceStateMapper.from(r), r, 5_000, zoomHandlesDistance = true)),
        )
        // 줌 한계 → 그때 GuidanceDirection.CLOSER.utterance
        assertEquals(listOf(GuidanceDirection.CLOSER.utterance), speech(policy.onJudgment(GuidanceStateMapper.from(r), r, 6_000, zoomHandlesDistance = false)))
        // 다른 축이 벗어나 있으면 그 축은 여전히 말한다
        val r2 = result(x = -0.30f, size = -0.30f)
        assertEquals(listOf(GuidanceDirection.Clock(11).utterance), speech(GuidancePolicy().onJudgment(GuidanceStateMapper.from(r2), r2, 0, zoomHandlesDistance = true)))
        // 너무 큰 것(FARTHER)은 "뒤로"를 말하지 않고, READY 도 막지 않는다 (2026-08-23) — 안정화 대기만
        val r3 = result(x = 0f, size = 0.30f)
        assertTrue(GuidancePolicy().onJudgment(GuidanceStateMapper.from(r3), r3, 0, zoomHandlesDistance = true).isEmpty())
    }

    @Test
    fun `ready is kept with hysteresis until deviation exceeds the exit factor`() {
        val policy = GuidancePolicy()
        policy.feed(result(0f, 0f), now = 0)
        assertEquals(listOf(GuidancePolicy.READY_UTTERANCE), speech(policy.feed(result(0f, 0f), now = 300)))
        // 0.25 는 진입 임계(0.20)는 넘지만 이탈 임계(0.30)는 안 넘음 → 여전히 READY(침묵)
        assertTrue(policy.feed(result(x = 0.25f, size = 0f), now = 600).isEmpty())
        // 0.35 → 이탈 → 방향 안내 (무게중심이 오른쪽 칸 바깥쪽 절반까지 가서 급한 구역 2시)
        assertEquals(listOf(GuidanceDirection.Clock(2).utterance), speech(policy.feed(result(x = 0.35f, size = 0f), now = 2_000)))
    }

    // ---- READY ----

    @Test
    fun `ready speaks once after the debounce and not again while held`() {
        val policy = GuidancePolicy()
        assertTrue(policy.feed(result(0f, 0f), now = 0).isEmpty()) // 아직 안정화 전
        assertEquals(listOf(GuidancePolicy.READY_UTTERANCE), speech(policy.feed(result(0f, 0f), now = 300)))
        assertTrue(policy.feed(result(0f, 0f), now = 600).isEmpty())
        assertTrue(nonPresence(policy.feed(result(0f, 0f), now = 10_000)).isEmpty())
    }

    @Test
    fun `predicted frames block the shutter without resetting a stable ready episode`() {
        val policy = GuidancePolicy()
        policy.feed(result(0f, 0f), now = 0L)
        assertEquals(
            listOf(GuidancePolicy.READY_UTTERANCE),
            speech(policy.feed(result(0f, 0f), now = 300L)),
        )
        assertTrue(policy.feed(
            result(
                0f,
                0f,
                freshness = ObservationFreshness.PREDICTED,
                ageMs = 100L,
            ),
            now = 400L,
        ).isEmpty())
        // 다음 fresh keyframe에 READY는 복원되지만 같은 에피소드 음성은 반복하지 않는다.
        assertTrue(policy.feed(result(0f, 0f), now = 600L).isEmpty())
    }

    @Test
    fun `uncertain observation never asks the user to move`() {
        val predicted = result(
            x = 0.8f,
            size = 0f,
            freshness = ObservationFreshness.PREDICTED,
            ageMs = 100L,
        )
        assertTrue(GuidancePolicy().feed(predicted, now = 2_000L).isEmpty())

        val stale = result(x = -0.8f, size = 0f, ageMs = 1_000L)
        assertTrue(GuidancePolicy().feed(stale, now = 2_000L).isEmpty())
    }

    @Test
    fun `hard uncertain exit starts a new ready speech episode after restabilization`() {
        val policy = GuidancePolicy()
        policy.feed(result(0f, 0f), now = 0L)
        assertEquals(
            listOf(GuidancePolicy.READY_UTTERANCE),
            speech(policy.feed(result(0f, 0f), now = GuidancePolicy.READY_DEBOUNCE_MS)),
        )

        val hardExitAt = GuidancePolicy.READY_RESPEAK_MS + 500L
        assertTrue(
            nonPresence(
                policy.feed(
                    result(
                        x = 0.8f,
                        size = 0f,
                        freshness = ObservationFreshness.PREDICTED,
                        ageMs = 100L,
                    ),
                    now = hardExitAt,
                ),
            ).isEmpty(),
        )
        assertTrue(policy.feed(result(0f, 0f), now = hardExitAt + 100L).isEmpty())
        assertEquals(
            listOf(GuidancePolicy.READY_UTTERANCE),
            speech(
                policy.feed(
                    result(0f, 0f),
                    now = hardExitAt + 100L + GuidancePolicy.READY_DEBOUNCE_MS,
                ),
            ),
        )
    }

    @Test
    fun `process judgment returns the exact canonical verdict used for actions`() {
        val policy = GuidancePolicy()
        val first = policy.processJudgment(
            GuidanceStateMapper.from(result(0f, 0f)),
            result(0f, 0f),
            nowMs = 0L,
        )
        assertTrue(ReadinessBlocker.UNSTABLE in first.verdict.blockers)
        assertTrue(first.actions.isEmpty())

        val stable = policy.processJudgment(
            GuidanceStateMapper.from(result(0f, 0f)),
            result(0f, 0f),
            nowMs = GuidancePolicy.READY_DEBOUNCE_MS,
        )
        assertTrue(stable.verdict.ready)
        assertEquals(listOf(GuidancePolicy.READY_UTTERANCE), speech(stable.actions))
    }

    @Test
    fun `ready flapping does not respeak within the respeak window`() {
        val policy = GuidancePolicy()
        policy.feed(result(0f, 0f), now = 0)
        policy.feed(result(0f, 0f), now = 300) // spoken
        policy.feed(result(0.40f, 0f), now = 400) // 이탈 임계(0.30) 초과 → READY 벗어남
        policy.feed(result(0f, 0f), now = 500)
        assertTrue(policy.feed(result(0f, 0f), now = 900).isEmpty()) // 3초 안이라 반복 없음
        assertEquals(
            listOf(GuidancePolicy.READY_UTTERANCE),
            speech(policy.feed(result(0f, 0f), now = 300 + GuidancePolicy.READY_RESPEAK_MS)),
        )
    }

    // ---- LOST ----

    @Test
    fun `brief loss is silent`() {
        val policy = GuidancePolicy()
        // 한 번 검출된 뒤의 이탈만 LOST 정책 — 첫 검출 전은 탐색 안내가 담당한다
        policy.feed(result(0.3f, 0f), now = 0)
        assertTrue(policy.feed(lostResult, now = 100).isEmpty())
        assertTrue(policy.feed(lostResult, now = 100 + GuidancePolicy.LOST_DEBOUNCE_MS - 1).isEmpty())
        // 다시 찾으면 에피소드 종료 — 아무 것도 안 나갔고 방향 안내가 바로 재개된다
        val back = policy.feed(result(0.3f, 0f), now = 1_500)
        assertEquals(listOf(GuidanceDirection.Clock(1).utterance), speech(back))
    }

    @Test
    fun `sustained loss beeps at intervals and speaks disappeared once`() {
        val policy = GuidancePolicy()
        policy.setSubject("피사체") // 지정된 세션만 "사라졌어요"를 말한다
        policy.feed(result(0.3f, 0f), now = 0) // 검출 후 이탈이어야 LOST 정책
        val lostAt = 100L
        policy.feed(lostResult, now = lostAt)
        val first = policy.feed(lostResult, now = lostAt + GuidancePolicy.LOST_DEBOUNCE_MS)
        assertEquals(listOf(GuidanceAction.WarningTone), first)
        assertTrue(policy.feed(lostResult, now = lostAt + GuidancePolicy.LOST_DEBOUNCE_MS + 1_000).isEmpty())

        // 2초 시점: "사라졌어요" 1회 (톤 간격은 아직이라 음성만)
        val spoken = policy.feed(lostResult, now = lostAt + GuidancePolicy.LOST_SPEAK_AFTER_MS)
        assertTrue(spoken.contains(GuidanceAction.Speak(GuidancePolicy.LOST_UTTERANCE)))
        // 이후 같은 에피소드에서는 음성 반복 없음 (톤만)
        val later = policy.feed(
            lostResult,
            now = lostAt + GuidancePolicy.LOST_DEBOUNCE_MS + GuidancePolicy.LOST_TONE_INTERVAL_MS,
        )
        assertEquals(listOf(GuidanceAction.WarningTone), later)
    }

    // ---- 존재 확인 진동 (사용자 요청 2026-08-24) ----

    @Test
    fun `presence vibration starts after sustained detection and stops immediately on loss`() {
        val policy = GuidancePolicy()
        policy.setSubject("강아지") // 존재 진동은 지정한 피사체 전용
        assertTrue(
            policy.feed(result(0.3f, 0f), now = 0)
                .none { it == GuidanceAction.PresenceVibrationStart },
        )
        val start = policy.feed(
            result(0.3f, 0f),
            now = GuidancePolicy.PRESENCE_VIBRATION_AFTER_MS,
        )
        assertTrue(start.contains(GuidanceAction.PresenceVibrationStart))
        // 벗어나는 순간 즉시 정지 — LOST 디바운스를 기다리지 않는다
        val stop = policy.feed(lostResult, now = GuidancePolicy.PRESENCE_VIBRATION_AFTER_MS + 100)
        assertTrue(stop.contains(GuidanceAction.PresenceVibrationStop))
    }

    @Test
    fun `undesignated session never announces found or disappeared and never buzzes`() {
        val policy = GuidancePolicy() // setSubject 없음 = 일반 촬영 (아무 물체나 잡힘)
        assertTrue(policy.feed(lostResult, now = 0).isEmpty())
        assertTrue(policy.feed(lostResult, now = GuidancePolicy.SEARCH_HINT_AFTER_MS).isEmpty())
        // 아무 물체가 잡혀도 "찾았어요" 없이 방향 안내로 직행
        val found = policy.feed(result(0.3f, 0f), now = GuidancePolicy.SEARCH_HINT_AFTER_MS + 1_100)
        assertEquals(listOf(GuidanceDirection.Clock(1).utterance), speech(found))
        // 오래 잡혀 있어도 존재 진동 없음
        val held = policy.feed(
            result(0.3f, 0f),
            now = GuidancePolicy.SEARCH_HINT_AFTER_MS + 1_100 + GuidancePolicy.PRESENCE_VIBRATION_AFTER_MS,
        )
        assertTrue(held.none { it == GuidanceAction.PresenceVibrationStart })
        // 이탈해도 "사라졌어요" 없음 (경고음만)
        val lost = policy.feed(lostResult, now = 10_000)
        val lostLater = policy.feed(lostResult, now = 10_000 + GuidancePolicy.LOST_SPEAK_AFTER_MS)
        assertTrue((lost + lostLater).none { it is GuidanceAction.Speak })
    }

    /** 존재 진동 시작/정지 액션을 뺀 나머지 — 오래 잡혀 있는 시나리오의 "그 외 침묵" 단언용. */
    private fun nonPresence(actions: List<GuidanceAction>): List<GuidanceAction> =
        actions.filterNot {
            it == GuidanceAction.PresenceVibrationStart || it == GuidanceAction.PresenceVibrationStop
        }

    // ---- 탐색 안내 (노션 스크립트 상태 3, 2026-08-24) ----

    @Test
    fun `initial searching hints then announces found without any tones`() {
        val policy = GuidancePolicy()
        policy.setSubject("피사체") // 발화로 대상이 지정된 세션
        assertTrue(policy.feed(lostResult, now = 0).isEmpty())
        assertTrue(policy.feed(lostResult, now = 1_000).isEmpty())
        val hint = policy.feed(lostResult, now = GuidancePolicy.SEARCH_HINT_AFTER_MS)
        assertEquals(listOf("피사체가 아직 안 보여요. 좌우로 천천히 움직여 주세요."), speech(hint))
        assertTrue(hint.none { it == GuidanceAction.WarningTone })
        val fail = policy.feed(lostResult, now = GuidancePolicy.SEARCH_FAIL_AFTER_MS)
        assertEquals(listOf("피사체를 못 찾았어요. 더 찾을까요, 다른 대상을 고를까요?"), speech(fail))
        val found = policy.feed(result(0.3f, 0f), now = GuidancePolicy.SEARCH_FAIL_AFTER_MS + 1_000)
        assertEquals(listOf("피사체를 찾았어요."), speech(found))
    }

    @Test
    fun `subject name flows into search sentences with correct josa`() {
        val policy = GuidancePolicy()
        policy.setSubject("강아지")
        policy.feed(lostResult, now = 0)
        val hint = policy.feed(lostResult, now = GuidancePolicy.SEARCH_HINT_AFTER_MS)
        assertEquals(listOf("강아지가 아직 안 보여요. 좌우로 천천히 움직여 주세요."), speech(hint))
        val found = policy.feed(result(0.3f, 0f), now = GuidancePolicy.SEARCH_HINT_AFTER_MS + 1_000)
        assertEquals(listOf("강아지를 찾았어요."), speech(found))
    }

    @Test
    fun `refind after spoken lost announces once and immediate detection start skips found`() {
        val policy = GuidancePolicy()
        policy.setSubject("피사체")
        // 시작부터 보이면 "찾았어요" 없이 방향 안내로 직행 (탐색 단계가 없었음)
        val direct = policy.feed(result(0.3f, 0f), now = 0)
        assertEquals(listOf(GuidanceDirection.Clock(1).utterance), speech(direct))
        // 긴 이탈로 "벗어났어요"까지 나간 뒤 재검출 → "다시 찾았어요." 1회
        val lostAt = 100L
        policy.feed(lostResult, now = lostAt)
        policy.feed(lostResult, now = lostAt + GuidancePolicy.LOST_SPEAK_AFTER_MS)
        val refound = policy.feed(result(0.3f, 0f), now = lostAt + GuidancePolicy.LOST_SPEAK_AFTER_MS + 500)
        assertEquals(listOf(GuidancePolicy.REFIND_UTTERANCE), speech(refound))
    }

    @Test
    fun `reset clears spoken state for a new session`() {
        val policy = GuidancePolicy()
        policy.feed(result(0f, 0f), now = 0)
        policy.feed(result(0f, 0f), now = 300)
        policy.reset()
        policy.feed(result(0f, 0f), now = 400)
        assertEquals(listOf(GuidancePolicy.READY_UTTERANCE), speech(policy.feed(result(0f, 0f), now = 700)))
    }

    // ---- READY 보류 (셀카 모드 시선 게이트, 2026-08-21) ----

    private fun GuidancePolicy.feedBlocked(r: DeviationResult, now: Long, reason: String?) =
        onJudgment(GuidanceStateMapper.from(r), r, now, readyBlockedReason = reason)

    @Test
    fun `ready with a block reason speaks the reason instead of shoot-now`() {
        val policy = GuidancePolicy()
        val actions = policy.feedBlocked(result(0f, 0f), now = 0, reason = "카메라를 봐 주세요")
        assertEquals(listOf("카메라를 봐 주세요"), speech(actions))
        // 보류 사유는 반복 간격 안에서는 다시 말하지 않는다
        assertTrue(policy.feedBlocked(result(0f, 0f), now = 1_000, reason = "카메라를 봐 주세요").isEmpty())
        val again = policy.feedBlocked(
            result(0f, 0f), now = GuidancePolicy.DIRECTION_REPEAT_MS, reason = "카메라를 봐 주세요",
        )
        assertEquals(listOf("카메라를 봐 주세요"), speech(again))
    }

    @Test
    fun `ready fires normally once the block reason clears`() {
        val policy = GuidancePolicy()
        policy.feedBlocked(result(0f, 0f), now = 0, reason = "카메라를 봐 주세요")
        // 시선이 돌아옴 — READY 디바운스(에피소드 시작 기준)를 지나 "지금 촬영하세요"
        val actions = policy.feedBlocked(
            result(0f, 0f), now = GuidancePolicy.READY_DEBOUNCE_MS + 100, reason = null,
        )
        assertEquals(listOf(GuidancePolicy.READY_UTTERANCE), speech(actions))
    }

    @Test
    fun `block reason does not affect sessions without one`() {
        val policy = GuidancePolicy()
        policy.feed(result(0f, 0f), now = 0)
        assertEquals(
            listOf(GuidancePolicy.READY_UTTERANCE),
            speech(policy.feed(result(0f, 0f), now = GuidancePolicy.READY_DEBOUNCE_MS + 100)),
        )
    }

    // ---- 하트비트 — 방향 단어가 없는 상태에서 죽은 공백을 없앤다 (2026-08-23) ----

    @Test
    fun `oversized subject no longer blocks READY`() {
        // 크기 초과(FARTHER)는 READY 를 막지 않는다 (2026-08-23) — 그대로 찍게 두고 후처리로 넘긴다
        val policy = GuidancePolicy()
        policy.feed(result(x = 0f, size = 0.5f), now = 0)
        assertEquals(
            listOf(GuidancePolicy.READY_UTTERANCE),
            speech(policy.feed(result(x = 0f, size = 0.5f), now = GuidancePolicy.READY_DEBOUNCE_MS + 100)),
        )
    }

    @Test
    fun `visibility-blocked state speaks a heartbeat instead of staying silent`() {
        val policy = GuidancePolicy()
        // 머리가 잘린 전신 구도 — x/y/size 는 다 맞는데 VISIBILITY 만 막힌 상태
        val cropped = result(
            x = 0f, size = 0f,
            visibility = FrameVisibility(
                leftMargin = 0.4f, topMargin = 0f, rightMargin = 0.4f, bottomMargin = 0.1f,
            ),
        )
        assertTrue(policy.feed(cropped, now = 0).isEmpty())
        val actions = policy.feed(cropped, now = GuidancePolicy.HEARTBEAT_AFTER_MS)
        assertEquals(listOf(GuidancePolicy.VISIBILITY_HEARTBEAT), speech(actions))
    }

    @Test
    fun `person session speaks a safe tell-the-subject message instead of the generic visibility heartbeat`() {
        // 사용자 요청 2026-08-27 — 인물 세션에서 머리·발이 잘릴 만큼 가까우면 "뒤로 가라"를
        // 촬영자 본인이 아니라 피사체(상대방)에게 전달하라고 안내해야 안전하다.
        val policy = GuidancePolicy()
        val cropped = result(
            x = 0f, size = 0f,
            visibility = FrameVisibility(
                leftMargin = 0.4f, topMargin = 0f, rightMargin = 0.4f, bottomMargin = 0.1f,
            ),
        )
        assertTrue(
            policy.onJudgment(GuidanceStateMapper.from(cropped), cropped, nowMs = 0, personSession = true)
                .isEmpty(),
        )
        val actions = policy.onJudgment(
            GuidanceStateMapper.from(cropped), cropped,
            nowMs = GuidancePolicy.HEARTBEAT_AFTER_MS, personSession = true,
        )
        assertEquals(listOf(GuidancePolicy.PERSON_TOO_CLOSE_HEARTBEAT), speech(actions))
    }

    @Test
    fun `heartbeat waits for both state persistence and speech silence`() {
        val policy = GuidancePolicy()
        policy.feed(result(x = 0.3f, size = 0f), now = 0) // GuidanceDirection.Clock(1).utterance
        val cropped = result(
            x = 0f, size = 0f,
            visibility = FrameVisibility(
                leftMargin = 0.4f, topMargin = 0f, rightMargin = 0.4f, bottomMargin = 0.1f,
            ),
        )
        // t=1s 부터 잘림 상태 — 상태 지속 4s 와 음성 공백 4s 를 모두 채워야 말한다
        assertTrue(policy.feed(cropped, now = 1_000).isEmpty())
        assertTrue(nonPresence(policy.feed(cropped, now = 4_500)).isEmpty())
        assertEquals(
            listOf(GuidancePolicy.VISIBILITY_HEARTBEAT),
            speech(policy.feed(cropped, now = 5_000)),
        )
    }
}
