package com.example.snap_sight.ux

/**
 * 인물 프레이밍 (2026-08-28) — 인물 세션에서 바운딩 박스가 화면에 다 들어오는지로 분기한다
 * (사용자 요청: "인물이라고 판정됐을 때 바운딩 박스가 화면에 다 안에 안 들어오면 분기").
 * 좌우는 기존 [GuidancePolicy] 3x5 시계 안내가 그대로 맡고, 이 클래스는 상하/줌 축만 본다.
 *
 *  1. bbox 가 화면을 벗어남([CanonicalReadinessEvaluator]의 VISIBILITY 블로커) — 너무 가까워
 *     전신이 안 들어오는 경우. 줌은 아예 걸지 않는다. 머리(코) 좌표가 [CASE1_HEAD_MIN]..
 *     [CASE1_HEAD_MAX] 사이면(위=0,아래=1) 목표 도달로 보고 진동 1회 — bbox 가 화면을
 *     가득 채워도 상관없다(사용자 요청 2026-08-28: "바운딩 박스는 신경쓰지 말라").
 *  2. bbox 가 화면 안에 다 들어옴 — 10%씩 점차 줌인([RequestZoomStep], 실제 반영 여부는
 *     [AutoZoomController] 쿨다운이 결정)하면서 머리·발(양 발목 평균) 좌표가
 *     [CASE2_HEAD_MIN]..[CASE2_HEAD_MAX] / [CASE2_FOOT_MIN]..[CASE2_FOOT_MAX] 에 들어오면
 *     목표 도달. 진동은 줌인을 "시작할 때"(첫 스텝) 1회, "끝났을 때"(목표 도달) 1회 — 두
 *     경계에서만.
 *
 * 두 경우 모두 목표 도달 후 [HOLD_MS](3초) 동안 유지되면 [Capture] 를 1회만 반환한다 —
 * READY 유지를 요구하는 기존 [AutoCaptureArbiter] 와 달리 인물 프레이밍 전용의 짧은 확정
 * 창이다. [GuidancePolicy] 처럼 android.* 의존이 없어 시각(nowMs)을 주입받아 테스트한다.
 *
 * bbox 높이가 [CLOSE_ENOUGH_HEIGHT_RATIO] 이상으로 [CLOSE_ENOUGH_HOLD_MS] 이상 유지되면
 * 머리/발 좌표와 무관하게 곧장 목표 도달로 본다(사용자 요청 2026-08-28) — 극근접에서는 포즈
 * 랜드마크가 자주 실패해 case 1의 머리 좌표 조건을 영영 못 채우고 "대상이 너무 가까워요"만
 * 반복되는 문제 대책. 한 프레임만 잠깐 넘는 건(검출 튐) 무시한다.
 *
 * case 1(줌이 불가능한 극근접)에서는 일반 구도 판정(READY, "좋아요" 음성)도 도달로
 * 인정한다(사용자 요청 2026-08-28) — 머리 목표 밴드가 일반 READY 기준보다 좁아 "좋아요는
 * 뜨는데 촬영은 안 된다"는 불일치가 있었다. case 2 에서는 인정하지 않는다 — 아직 멀리
 * 있어도 일반 구도만 맞으면 줌을 걸기도 전에 곧장 촬영돼버리기 때문(사용자 요청 2026-08-28
 * — "거기까지 안 가고 멀어도 지금 촬영이 자꾸 되잖아 클로즈업을 안 하고").
 *
 * 줌은 피사체가 **정지해 있을 때만** 건다(엔드유저 피드백 2026-08-30 — "아기·강아지처럼
 * 움직이는 피사체는 줌하면 화면에서 벗어난다"). [onJudgment] 의 `subjectMoving`
 * ([SubjectMotionDetector] 판정)이 true 인 동안은 case 2 라도 줌 스텝을 요청하지 않고
 * 기다린다 — 이미 걸린 배율을 되돌리지는 않는다.
 *
 * 구도 모드(2026-08-31, "구도 좋게 찍어줘"류 발화): 목표 밴드를 큐레이션된 인물 사진
 * (Wikimedia Commons)에 앱과 같은 YOLO+포즈 좌표 추출을 돌려 얻은 실측 분포
 * (`ai/tools/composition_stats.py`)로 바꾼다 — "잘 찍힌 사진들이 실제로 쓰는" 배치다.
 * 범위([CompositionScope])는 둘로 나뉜다:
 *  - [CompositionScope.FULL_BODY] — case 2 밴드를 전신 사진 분포
 *    ([COMPOSITION_HEAD_MIN]..[COMPOSITION_FOOT_MAX])로 교체.
 *  - [CompositionScope.UPPER_BODY] — 발이 프레임 밖으로 나가고(footY null), 골반이 하단
 *    근처([UPPER_BODY_HIP_MIN] 이상) 또는 프레임 밖(hipY null)이 될 때까지 계속 줌인하고,
 *    머리가 상반신 사진 분포([COMPOSITION_UPPER_HEAD_MIN]..[COMPOSITION_UPPER_HEAD_MAX])에
 *    들어오면 도달 (2026-08-31 개편 — 기존 "bbox 넓이 65%" 기준은 마른 체형에서 영영 못
 *    채워 상한 6배까지 과확대되던 원인이라 폐기: "너무 줌인되거나 발목만 잘린 전신").
 *    하반신까지 다 보이는 상태에서 "상반신에 집중해서 찍을까요?"라고 물어 "네"면
 *    이 범위가 된다(사용자 요청 2026-08-31).
 *
 * 줌 안전장치 (2026-08-31): 중앙 기준 줌은 중심 위쪽을 더 위로 밀어낸다 — 한 스텝
 * (×[ZOOM_STEP_PREVIEW]) 뒤 머리가 [HEAD_TOP_GUARD] 위로 나갈 상황이면 어느 모드든 더
 * 줌하지 않는다 (머리 잘림·오버슈트 방지).
 */
internal class PersonFramingController {

    /** 구도 모드의 촬영 범위 — OFF 는 일반 인물 세션(기본 밴드). */
    internal enum class CompositionScope { OFF, FULL_BODY, UPPER_BODY }

    internal sealed interface Action {
        object None : Action
        /** 10% 줌인 요청 — 쿨다운 중이면 호출부가 무시해도 된다. */
        object RequestZoomStep : Action
        /** 목표를 [HOLD_MS] 동안 유지 — 자동촬영을 요청한다. 세션당 1회만. */
        object Capture : Action
    }

    internal data class Outcome(
        val action: Action = Action.None,
        /** 짧은 확정 진동 1회 — case 1: 좌표 도달, case 2: 줌 시작/줌 종료(도달) 경계. */
        val vibrate: Boolean = false,
        /**
         * 구도 모드의 줌-후 목표 도달 (2026-08-31) — 촬영은 여기서 확정하지 않는다. 호출부
         * (MainActivity)가 인물 위치를 말해주고 "이대로 찍을까요?"를 물은 뒤 셔터를 결정한다.
         * 3초 유지 자동촬영은 이 흐름으로 대체됐다 (case 1 극근접·비구도 세션은 기존 유지).
         */
        val targetReached: Boolean = false,
    )

    private var holdSinceMs: Long? = null
    private var zoomStarted = false
    private var fired = false
    private var closeEnoughSinceMs: Long? = null

    /** 새 인물 세션 시작 — 이전 세션의 진행 상태를 지운다. */
    fun reset() {
        holdSinceMs = null
        zoomStarted = false
        fired = false
        closeEnoughSinceMs = null
    }

    /**
     * 매 판정마다 호출한다.
     *
     * @param bboxFitsFrame false 면 case 1(바운딩 박스가 화면을 벗어남), true 면 case 2.
     * @param headY 코(머리 대용) 정규화 y — 미검출이면 null.
     * @param footY 양 발목 평균(발 대용) 정규화 y — 미검출이면 null.
     * @param bboxHeightRatio 사람 bbox 높이(프레임 대비 0..1) — 미검출이면 null.
     *        [CLOSE_ENOUGH_HEIGHT_RATIO] 이상이면 머리/발 좌표를 못 잡아도(극근접에서 포즈
     *        랜드마크가 자주 실패) 곧장 목표 도달로 보고 줌도 멈춘다(사용자 요청 2026-08-28 —
     *        "자꾸 피사체가 가까이 있어요 라고 뜬다").
     * @param generalReady 일반 구도 판정([ReadinessVerdict.ready], "좋아요" 음성과 같은 조건) —
     *        case 1(bboxFitsFrame=false)에서만 도달 인정에 쓴다. case 2 에서는 무시한다 —
     *        아직 멀리 있어도 인정하면 줌을 걸기도 전에 촬영돼버리기 때문(사용자 요청
     *        2026-08-28).
     * @param subjectMoving 피사체가 움직이는 중([SubjectMotionDetector]) — true 면 case 2 의
     *        줌 스텝을 보류한다(줌은 정지 피사체에만, 2026-08-30). 목표 도달·촬영 판정에는
     *        영향을 주지 않는다.
     * @param composition "구도 좋게 찍어줘"류 발화(2026-08-31)의 촬영 범위 — 목표 밴드를
     *        큐레이션 사진 실측 분포로 바꾼다. 클래스 주석의 [CompositionScope] 참고.
     * @param hipY 좌우 골반 평균 정규화 y — 미검출/프레임 밖이면 null. 상반신 구도의 줌 종료
     *        판정에만 쓴다 (2026-08-31 개편).
     */
    fun onJudgment(
        bboxFitsFrame: Boolean,
        headY: Float?,
        footY: Float?,
        bboxHeightRatio: Float?,
        generalReady: Boolean,
        nowMs: Long,
        subjectMoving: Boolean = false,
        composition: CompositionScope = CompositionScope.OFF,
        hipY: Float? = null,
    ): Outcome {
        if (fired) return Outcome()

        // 80% 이상이 잠깐 한 프레임만 튀어도 바로 멈추지 않도록 [CLOSE_ENOUGH_HOLD_MS] 이상
        // 연속으로 유지돼야 인정한다(사용자 요청 2026-08-28 — "80% 이상으로 1초 이상 그래야
        // 줌을 멈추게 해줘").
        val rawCloseEnough = bboxHeightRatio != null && bboxHeightRatio >= CLOSE_ENOUGH_HEIGHT_RATIO
        val closeEnough = if (rawCloseEnough) {
            val since = closeEnoughSinceMs ?: nowMs.also { closeEnoughSinceMs = it }
            nowMs - since >= CLOSE_ENOUGH_HOLD_MS
        } else {
            closeEnoughSinceMs = null
            false
        }
        // generalReady 는 case 1(줌 자체가 불가능한 극근접)에서만 도달로 인정한다 — case 2 에서
        // 허용하면 아직 멀리 있어도(일반 구도만 맞으면) 줌을 걸기도 전에 곧장 촬영돼버린다
        // (사용자 요청 2026-08-28 — "거기까지 안 가고 멀어도 지금 촬영이 자꾸 되잖아").
        val fullBody = composition == CompositionScope.FULL_BODY
        val upperBody = composition == CompositionScope.UPPER_BODY
        // case 1 머리 밴드 — 상반신 구도면 상반신 사진 실측 분포로 교체
        val case1HeadMin = if (upperBody) COMPOSITION_UPPER_HEAD_MIN else CASE1_HEAD_MIN
        val case1HeadMax = if (upperBody) COMPOSITION_UPPER_HEAD_MAX else CASE1_HEAD_MAX
        // case 2 머리·발 밴드 — 전신 구도면 전신 사진 실측 분포로 교체
        val headMin = if (fullBody) COMPOSITION_HEAD_MIN else CASE2_HEAD_MIN
        val headMax = if (fullBody) COMPOSITION_HEAD_MAX else CASE2_HEAD_MAX
        val footMin = if (fullBody) COMPOSITION_FOOT_MIN else CASE2_FOOT_MIN
        val footMax = if (fullBody) COMPOSITION_FOOT_MAX else CASE2_FOOT_MAX
        // 줌 안전장치 (2026-08-31): 중앙 기준 줌은 중심 위쪽을 더 위로 밀어낸다 — 한 스텝 뒤
        // 머리가 [HEAD_TOP_GUARD] 위로 나갈 상황이면 더 줌하지 않는다 (머리 잘림·오버슈트 방지).
        val headAfterStep = headY?.let { 0.5f - (0.5f - it) * ZOOM_STEP_PREVIEW }
        val zoomWouldCutHead = headAfterStep != null && headAfterStep < HEAD_TOP_GUARD
        val onTarget = closeEnough || if (upperBody) {
            // 상반신 구도 (2026-08-31 개편): 발이 안 보이고(footY null), 골반이 하단 근처 또는
            // 프레임 밖이고, 머리가 상반신 밴드에 들어오면 도달 — 기존 "bbox 넓이 65%" 기준은
            // 마른 체형에서 영영 못 채워 상한까지 과확대되던 원인이라 폐기했다. 머리가 더
            // 줌하면 잘릴 만큼 높아도 발이 이미 밖이면 최선 도달로 인정한다(무한 대기 방지).
            // generalReady 는 전신 기준 판정이라 여기선 쓰지 않는다.
            headY != null && footY == null &&
                (hipY == null || hipY >= UPPER_BODY_HIP_MIN) &&
                (headY in case1HeadMin..case1HeadMax || zoomWouldCutHead)
        } else if (!bboxFitsFrame) {
            generalReady || (headY != null && headY in case1HeadMin..case1HeadMax)
        } else {
            headY != null && headY in headMin..headMax &&
                footY != null && footY in footMin..footMax
        }

        if (!onTarget) {
            holdSinceMs = null
            // case 1(프레임 초과)은 줌을 걸지 않는다 — 단, 상반신 구도는 목표까지 계속
            // 줌인해야 하므로 예외다. close-enough(이미 충분히 큼)는 두 경우 모두 줌 없음.
            if ((!bboxFitsFrame && !upperBody) || closeEnough) return Outcome()
            // 움직이는 피사체에는 줌을 걸지 않는다 — 멈출 때까지 기다린다 (2026-08-30).
            // zoomStarted 는 그대로 둔다: 첫 실제 스텝에서 진동하는 규약을 유지하기 위해.
            if (subjectMoving) return Outcome()
            // 다음 스텝이 머리를 프레임 위로 밀어낼 상황이면 줌하지 않는다 (모든 모드 공통).
            if (zoomWouldCutHead) return Outcome()
            // 상반신은 머리 좌표 없이는 멈출 조건이 없다 — 포즈가 잡힐 때까지 확대를 보류한다.
            if (upperBody && headY == null) return Outcome()
            val firstStep = !zoomStarted
            zoomStarted = true
            return Outcome(Action.RequestZoomStep, vibrate = firstStep)
        }

        val since = holdSinceMs ?: nowMs.also { holdSinceMs = it }
        val justReached = since == nowMs
        // 구도 모드의 줌-후 도달(case 2 전신, 또는 상반신 목표)은 촬영을 여기서 확정하지
        // 않는다 — 호출부가 인물 위치("오른쪽/왼쪽/가운데")를 물어보고 셔터를 결정한다
        // (사용자 요청 2026-08-31, 3초 유지 자동촬영 대체). case 1 극근접(줌 불가)과 비구도
        // 세션은 기존 3초 유지 규약을 그대로 쓴다.
        val questionFlow = composition != CompositionScope.OFF && (bboxFitsFrame || upperBody)
        if (questionFlow) {
            return Outcome(vibrate = justReached, targetReached = true)
        }
        if (nowMs - since >= HOLD_MS) {
            fired = true
            return Outcome(Action.Capture, vibrate = justReached)
        }
        return Outcome(vibrate = justReached)
    }

    companion object {
        // case 1 — 화면을 벗어난 경우: 극근접이라 줌은 못 걸어도, 머리는 일반 인물 사진처럼
        // 위쪽 여백에 오면 충분하다(사용자 요청 2026-08-28 — "얼굴만 0.1~0.3 사이에 좌표가
        // 있으면 그냥 자동촬영"). 위=0, 아래=1.
        const val CASE1_HEAD_MIN = 0.20f
        const val CASE1_HEAD_MAX = 0.40f

        // case 2 — 화면 안에 다 들어온 경우: 머리는 위쪽 여백, 발은 아래쪽에 오도록 줌인한다.
        // 2026-08-28 실기기 튜닝 결과로 고정(사용자 요청 — "줌은 이제 안 건드려도 돼 지금으로
        // 픽스해줘").
        const val CASE2_HEAD_MIN = 0.00f
        const val CASE2_HEAD_MAX = 0.15f
        const val CASE2_FOOT_MIN = 0.60f
        const val CASE2_FOOT_MAX = 0.80f

        // 구도 모드 (2026-08-31) — 큐레이션 인물 사진(Wikimedia Commons Quality Images +
        // Unsplash 전신 포트레이트, 단독 인물+포즈 검출 성공 111장)에 앱과 같은 YOLO+포즈
        // 추출(`ai/tools/composition_stats.py`)을 돌려 얻은 실측 분포의 p25~p75 를 반올림한
        // 구간 — 머리(코) 0.193~0.340, 발(발목 평균) 0.743~0.872 (발 상한은 하단 잘림 여유로
        // 0.85 에 묶음). 기본 밴드보다 머리 위 여백을 더 두는 배치다. 데이터셋을 늘리면 이
        // 값만 갱신한다.
        const val COMPOSITION_HEAD_MIN = 0.20f
        const val COMPOSITION_HEAD_MAX = 0.35f
        const val COMPOSITION_FOOT_MIN = 0.75f
        const val COMPOSITION_FOOT_MAX = 0.85f

        // 상반신 구도 (2026-08-31) — 상반신(발목이 안 보이는) 큐레이션 사진 52장의 머리(코) y
        // 분포 p25~p75(0.280~0.388)를 반올림한 case 1 목표 밴드. 데이터셋을 늘리면 이 값만
        // 갱신한다.
        const val COMPOSITION_UPPER_HEAD_MIN = 0.25f
        const val COMPOSITION_UPPER_HEAD_MAX = 0.40f

        /**
         * 상반신 구도의 줌 종료 조건 (2026-08-31 개편) — 골반이 이 y(하단 15% 띠) 아래로
         * 내려오거나 프레임 밖(hipY null)이어야 "하반신이 잘린 상반신 구도"다.
         * 근거(인체 비례): 코≈키의 93%·골반≈53% 높이라, 머리 밴드 중앙(0.30)에서 골반이 0.75면
         * 화면 하단이 무릎 근처다 — 상반신이 아니라 무릎샷 (사용자 지적 2026-08-31). 진짜
         * 허리~골반 컷은 골반이 하단에 붙는다(≈0.95+). ML Kit 이 가장자리 랜드마크를 놓쳐
         * null 로 빠지는 것까지 감안해 0.85 를 하한으로 둔다.
         */
        const val UPPER_BODY_HIP_MIN = 0.85f

        /**
         * 한 스텝 뒤 머리가 이 y 위로 나가면 줌을 멈춘다 (머리 잘림 방지). 기본 case 2 밴드가
         * 머리 0~15% 를 허용하므로, 밴드 안의 정상 줌을 막지 않도록 "정말 잘리기 직전"에만 건다.
         */
        const val HEAD_TOP_GUARD = 0.01f

        /**
         * 줌 한 스텝의 배율 미리보기 — [com.example.snap_sight.camera.AutoZoomController.PERSON_FRAMING_ZOOM_STEP]
         * 과 같은 값 (이 파일은 android 의존이 없어 값만 복제; 그쪽을 바꾸면 여기도 바꾼다).
         */
        const val ZOOM_STEP_PREVIEW = 1.15f

        /** 목표 도달 후 이만큼 유지되면 자동촬영 (사용자 요청 2026-08-28 — "3초 안에"). */
        const val HOLD_MS = 3_000L

        /** bbox 높이가 프레임의 이 비율 이상이면 정밀 좌표 없이도 "충분히 가까움". */
        const val CLOSE_ENOUGH_HEIGHT_RATIO = 0.80f

        /** [CLOSE_ENOUGH_HEIGHT_RATIO] 이상이 이만큼 연속 유지돼야 인정한다(잠깐 튀는 것 방지). */
        const val CLOSE_ENOUGH_HOLD_MS = 1_000L
    }
}
