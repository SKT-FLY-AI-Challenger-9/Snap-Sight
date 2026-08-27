// 이 파일: 세션 배율 관리.
// 현재 배율은 **1.0배로 고정**돼 있다 ([SESSION_START_ZOOM] == [BASE_ZOOM]) — 2026-08-23 피드백:
// 세션 시작 0.6배 광각 → 구도 안정 시 1.0배 복귀 → 촬영 후 다시 0.6배 라는 왕복이 화면을
// 보는 사용자(잔존시력·조력자)에게 어지러웠다. 배율이 변하지 않으므로 아래 복귀 로직들은
// 사실상 동작하지 않지만, 되돌리기 쉽도록 구현은 그대로 둔다.
//  - 광각 탐색을 되살리려면 [SESSION_START_ZOOM] 을 다시 0.6f 로 되돌리면 된다.
// 면적 기반 자동 "줌인"(멀리 있는 피사체를 프레이밍 목표 크기로 당기기)도 구현은 남겨두되
// **비활성화**돼 있다([ZOOM_IN_ENABLED]) — 2026-08-19 피드백: bbox 면적만으로는 깊이 판단이 부정확해
// 확대가 오히려 혼란을 줬다. 깊이 추정/후처리가 붙은 뒤 다시 켠다.
package com.example.snap_sight.camera

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

class AutoZoomController(private val cameraController: CameraController) {

    @Volatile
    private var lastZoomAtMs = 0L

    @Volatile
    private var alignedStreak = 0

    @Volatile
    private var noTargetStreak = 0

    /**
     * 이번 세션만 면적 기반 자동 줌인을 켠다 — [ZOOM_IN_ENABLED]는 여전히 전역 기본값(꺼짐)이고,
     * 인물 세션에서만 MainActivity가 이 값을 true로 켠다(사용자 요청 2026-08-27 — "인물이면
     * 무게중심 가이드 후 줌인해서 찍어줘"). 세션 시작마다 [reset]에서 false로 되돌아간다.
     */
    @Volatile
    var sessionZoomInEnabled: Boolean = false

    private val zoomInActive: Boolean get() = ZOOM_IN_ENABLED || sessionZoomInEnabled

    /**
     * CV 분석 스레드에서 매 프레임 호출된다.
     *
     * @param areaRatio  피사체 면적 / 프레임 면적
     * @param targetArea 프레이밍별 목표 면적비 (`DeviationJudgment.TARGET_AREA_RATIO`, READY 판정과 같은 값)
     * @param aligned    x/y/visibility가 허용치 안인지. **구도가 [ALIGN_FRAMES] 관측 프레임 연속**이어야 줌인한다 —
     *                   피사체가 프레임을 들락거리는 동안 배율이 널뛰지 않게.
     * @param hold       true 면 줌을 건드리지 않는다 (READY 유지 중)
     */
    fun onTargetArea(areaRatio: Float, targetArea: Float, aligned: Boolean, hold: Boolean = false) {
        noTargetStreak = 0
        alignedStreak = if (aligned) alignedStreak + 1 else 0
        if (alignedStreak < ALIGN_FRAMES) return
        val current = cameraController.zoomRatio
        // 광각(0.6)은 "찾기용"이다. 피사체가 잡혀 구도가 안정되면(기준 충족) 기본 배율 1.0 으로 돌아온다 —
        // 사진은 항상 정상 배율 이상으로 찍혀야 하고, READY 판정도 그 배율에서 해야 의미가 있다.
        if (current < BASE_ZOOM - ZOOM_EPS) {
            lastZoomAtMs = System.currentTimeMillis()
            cameraController.setZoomRatio(BASE_ZOOM)
            return
        }
        if (!zoomInActive || hold) return
        val now = System.currentTimeMillis()
        if (now - lastZoomAtMs < COOLDOWN_MS) return
        // 인물이 화면의 60%를 넘게 채우면(너무 가까움) 줌아웃부터 — 줌인과 같은 게이트
        // (정렬 유지·쿨다운·인물 세션 한정)를 그대로 쓴다(사용자 요청 2026-08-27).
        if (needsZoomOut(areaRatio)) {
            val next = nextZoomOut(current, areaRatio, ZOOM_OUT_TRIGGER_AREA, cameraController.minZoomRatio)
                ?: return
            lastZoomAtMs = now
            cameraController.setZoomRatio(next)
            return
        }
        val next = nextZoom(current, areaRatio, targetArea, cameraController.maxZoomRatio) ?: return
        lastZoomAtMs = now
        cameraController.setZoomRatio(next)
    }

    /**
     * AIMING 중 타겟을 못 잡은 프레임마다 호출 — 광각(1.0 미만)에서 [NO_TARGET_FRAMES] 연속 실패하면
     * 기본 배율 1.0 으로 복귀한다. 광각은 인식률이 낮아 "못 봐서 광각을 못 벗어나는" 악순환을 끊는 폴백.
     */
    fun onNoTarget() {
        noTargetStreak++
        if (noTargetStreak < NO_TARGET_FRAMES) return
        noTargetStreak = 0
        if (cameraController.zoomRatio < BASE_ZOOM - ZOOM_EPS) {
            lastZoomAtMs = System.currentTimeMillis()
            cameraController.setZoomRatio(BASE_ZOOM)
            Log.i("SnapSightZoom", "광각 탐색 실패 — 기본 배율 %.1f 로 복귀".format(BASE_ZOOM))
        }
    }

    /**
     * 줌인 여유가 남아 있는지 — GuidancePolicy 가 "가까이" 대신 줌에 맡길지 판단하는 데 쓴다.
     * 자동 줌인이 꺼져 있으면 항상 false (음성이 그대로 안내한다).
     */
    val canZoomIn: Boolean
        get() = zoomInActive &&
            cameraController.zoomRatio < effectiveMaxZoom(cameraController.maxZoomRatio) - ZOOM_EPS

    /**
     * True while zoom control can still resolve a too-small target without asking the user
     * to move. This includes the always-enabled 0.6x -> 1.0x base-zoom return even when
     * optional area-based zoom-in is disabled.
     */
    val canResolveSmallTarget: Boolean
        get() = canResolveSmallTarget(
            currentZoom = cameraController.zoomRatio,
            deviceMax = cameraController.maxZoomRatio,
            zoomInEnabled = zoomInActive,
        )

    // 세션 시작·종료 시 호출 — [SESSION_START_ZOOM] 로 돌아간다. 기기 배율 범위로 클램프된다.
    // 지금은 그 값이 1.0배라 실제로는 배율이 움직이지 않는다 (파일 상단 참고).
    fun reset() {
        lastZoomAtMs = 0L
        alignedStreak = 0
        noTargetStreak = 0
        sessionZoomInEnabled = false
        cameraController.setZoomRatio(SESSION_START_ZOOM)
        Log.i(
            "SnapSightZoom",
            "세션 시작 줌 요청 %.2f → 기기 범위 [%.2f, %.2f] 로 클램프".format(
                SESSION_START_ZOOM, cameraController.minZoomRatio, cameraController.maxZoomRatio,
            ),
        )
    }

    companion object {
        /**
         * 면적 기반 자동 줌인 on/off. 현재 **off** — bbox 면적만으로는 깊이가 부정확해 확대가 혼란을 줬다.
         * 켜면 [nextZoom] 규칙(줌인만, 한 번에 ≤1.5배, 상한 3배, 2초 간격)이 다시 동작한다.
         */
        const val ZOOM_IN_ENABLED = false
        /** 이 이상 확대하지 않는다 — 화질·손떨림 악화. 기기 max 가 더 작으면 그 값. */
        const val MAX_ZOOM = 3.0f
        /** 한 번에 이 배율 이상 건너뛰지 않는다 (0.6 → 2.0 같은 급격한 변화 방지). */
        const val MAX_STEP = 1.5f
        /** 목표 − 이 값보다 작을 때만 줌인한다 — READY 의 size 허용 오차와 같은 값. */
        const val TRIGGER_MARGIN = 0.10f
        /** 인물이 화면 면적의 이 비율을 넘게 채우면(너무 가까움) 줌아웃한다 (사용자 요청 2026-08-27). */
        const val ZOOM_OUT_TRIGGER_AREA = 0.60f
        /** 줌인 전 구도 정렬이 유지돼야 하는 연속 실제 관측 프레임 수. */
        const val ALIGN_FRAMES = 5
        /** 광각에서 1.0배 복귀까지 허용하는 연속 타겟 미탐지 프레임 수 (약 2초 @ 3fps). */
        const val NO_TARGET_FRAMES = 6
        /**
         * 세션 시작·종료 시 돌아가는 배율. **[BASE_ZOOM] 과 같은 값이라 배율이 고정된다** —
         * 0.6f 로 바꾸면 예전처럼 광각으로 넓게 피사체를 찾고 구도가 잡히면 1.0배로 복귀한다
         * (2026-08-23 피드백으로 껐다: 배율 왕복이 어지럽다).
         */
        const val SESSION_START_ZOOM = 1.0f
        /** 피사체를 찾은 뒤 돌아오는 기본 배율. 촬영은 항상 이 값 이상에서 한다. */
        const val BASE_ZOOM = 1.0f
        const val COOLDOWN_MS = 2_000L
        private const val ZOOM_EPS = 0.05f

        internal fun effectiveMaxZoom(deviceMax: Float): Float = minOf(MAX_ZOOM, deviceMax)

        internal fun canResolveSmallTarget(
            currentZoom: Float,
            deviceMax: Float,
            zoomInEnabled: Boolean = ZOOM_IN_ENABLED,
        ): Boolean = currentZoom < BASE_ZOOM - ZOOM_EPS ||
            (zoomInEnabled && currentZoom < effectiveMaxZoom(deviceMax) - ZOOM_EPS)

        /** 이 면적이면 줌인 대상인지 (너무 큰 경우는 [needsZoomOut] 이 따로 다룬다). */
        internal fun needsZoomIn(areaRatio: Float, targetArea: Float): Boolean =
            areaRatio > 0f && targetArea > 0f && areaRatio < targetArea - TRIGGER_MARGIN

        /** 인물이 화면을 [triggerArea] 넘게 채우는지 — 너무 가까움, 줌아웃 대상. */
        internal fun needsZoomOut(areaRatio: Float, triggerArea: Float = ZOOM_OUT_TRIGGER_AREA): Boolean =
            areaRatio > triggerArea

        /**
         * 다음 줌아웃 배율. 필요 없거나(면적이 기준 이하) 더 줄일 여유가 없으면(기기 최소 배율
         * 도달) null. [nextZoom] 과 대칭 — 면적은 줌의 제곱에 비례하므로 목표 배율 = 현재 ×
         * √(기준면적/현재면적); 한 번에 [MAX_STEP] 배 이상 줄이지 않고, 기기 최소 배율 아래로는
         * 안 내려간다(초광각 없는 기기는 사실상 1.0배에서 멈춤).
         */
        internal fun nextZoomOut(
            currentZoom: Float,
            areaRatio: Float,
            triggerArea: Float,
            deviceMin: Float,
        ): Float? {
            if (!needsZoomOut(areaRatio, triggerArea)) return null
            if (currentZoom <= deviceMin + ZOOM_EPS) return null
            val desired = currentZoom * sqrt(triggerArea / areaRatio)
            val next = maxOf(desired, currentZoom / MAX_STEP, deviceMin)
            return if (abs(next - currentZoom) < ZOOM_EPS) null else next
        }

        /**
         * 다음 줌 배율. 줌인이 필요 없거나 더 당길 여유가 없으면 null.
         * 면적은 줌의 제곱에 비례하므로 목표 배율 = 현재 × √(목표면적/현재면적); 한 번에 [MAX_STEP] 까지만,
         * [effectiveMaxZoom] 을 넘지 않는다.
         */
        internal fun nextZoom(currentZoom: Float, areaRatio: Float, targetArea: Float, deviceMax: Float): Float? {
            if (!needsZoomIn(areaRatio, targetArea)) return null
            val ceiling = effectiveMaxZoom(deviceMax)
            if (currentZoom >= ceiling - ZOOM_EPS) return null
            val desired = currentZoom * sqrt(targetArea / areaRatio)
            val next = minOf(desired, currentZoom * MAX_STEP, ceiling)
            return if (abs(next - currentZoom) < ZOOM_EPS) null else next
        }
    }
}
