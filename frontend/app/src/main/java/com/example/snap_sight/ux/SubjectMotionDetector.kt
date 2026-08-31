// 이 파일: 피사체가 "움직이는 중"인지 bbox 궤적으로 판정하는 순수 로직 (2026-08-30).
// 줌은 정적인 피사체에만 건다(엔드유저 피드백 2026-08-30 — "아기·강아지처럼 빠른 피사체는
// 줌하면 화면에서 벗어난다") — 판정 결과는 [PersonFramingController.onJudgment] 의
// subjectMoving 으로 들어가 case 2 줌 스텝을 막는다. Android 의존성 없이 시각을 주입받아
// 단위 테스트한다([GuidancePolicy] 와 같은 규약).
package com.example.snap_sight.ux

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 프레임 정규화 좌표의 bbox 중심·크기 변화율로 움직임을 판정한다.
 *
 * 규칙:
 *  - 최소 [MIN_JUDGE_DT_MS] 이상 떨어진 두 실관측만 비교한다 — 검출기 bbox 지터(프레임당 2~3%)를
 *    짧은 dt 로 나누면 속도가 부풀려지므로, 앵커 관측을 그 간격마다만 갈아 끼운다.
 *  - 중심 이동 속도(프레임 폭 단위/초)가 [movingSpeedThreshold] 이상이거나, 크기(√면적비)
 *    상대 변화율이 [movingScaleRateThreshold] 이상이면 "움직임" — [movingHoldMs] 동안 유지한다.
 *  - 줌 배율이 바뀐 사이의 관측은 비교하지 않는다 — 줌은 중심 오프셋·크기를 모두 바꿔
 *    가만히 있는 사람도 움직인 것처럼 보이게 한다.
 *  - 카메라 자체가 빠르게 도는 동안([cameraRotationThresholdRadPerS] 이상, 자이로 누적량 차이)
 *    은 피사체 움직임을 판별할 수 없으므로 판정을 건너뛰고 정지 확인 타이머를 다시 시작한다.
 *    부호·축이 실기기에서 검증되지 않은 값이라 보정(빼기)이 아니라 크기 게이트로만 쓴다
 *    ([com.example.snap_sight.camera.CameraMotionEstimator] KDoc 참고).
 *  - "정지"는 움직임 유지 창이 끝나고 [staticConfirmMs] 이상 정지 관측이 이어져야 인정한다
 *    ([isStatic]) — 세션 첫 프레임이나 카메라를 흔든 직후에 곧장 줌이 걸리지 않게.
 */
class SubjectMotionDetector(
    private val movingSpeedThreshold: Float = MOVING_SPEED_PER_S,
    private val movingScaleRateThreshold: Float = MOVING_SCALE_RATE_PER_S,
    private val movingHoldMs: Long = MOVING_HOLD_MS,
    private val staticConfirmMs: Long = STATIC_CONFIRM_MS,
    private val cameraRotationThresholdRadPerS: Float = CAMERA_ROTATION_RAD_PER_S,
) {
    private class Sample(
        val centerX: Float,
        val centerY: Float,
        val scale: Float,
        val zoomRatio: Float,
        val cameraOrientationRad: Pair<Float, Float>?,
        val atMs: Long,
    )

    private var anchor: Sample? = null
    private var movingUntilMs: Long = Long.MIN_VALUE / 2
    private var staticSinceMs: Long? = null

    /** 새 세션 — 이전 궤적·판정을 지운다. */
    fun reset() {
        anchor = null
        movingUntilMs = Long.MIN_VALUE / 2
        staticSinceMs = null
    }

    /**
     * 실관측(detector FRESH) 프레임마다 호출한다. 예측/유지(HELD) 프레임은 넣지 않는다 —
     * 트래커 예측은 등속 가정이라 움직임으로 오판한다.
     *
     * @param centerX bbox 중심 x (0..1, 프레임 정규화)
     * @param centerY bbox 중심 y (0..1)
     * @param scale bbox 크기 척도 — √(면적비) 처럼 길이에 비례하는 값(0..1)
     * @param zoomRatio 이 프레임의 카메라 배율
     * @param cameraOrientationRad 조준 시작 이후 누적 카메라 회전량(yaw, pitch) — 없으면 null
     * @return 호출 시점의 [isMoving]
     */
    fun onObservation(
        centerX: Float,
        centerY: Float,
        scale: Float,
        zoomRatio: Float,
        cameraOrientationRad: Pair<Float, Float>?,
        nowMs: Long,
    ): Boolean {
        val sample = Sample(centerX, centerY, scale, zoomRatio, cameraOrientationRad, nowMs)
        val previous = anchor
        if (previous == null) {
            anchor = sample
            staticSinceMs = nowMs
            return isMoving(nowMs)
        }
        val dtMs = nowMs - previous.atMs
        if (dtMs < MIN_JUDGE_DT_MS) return isMoving(nowMs) // 앵커를 유지한 채 다음 관측을 기다린다
        anchor = sample
        if (dtMs > MAX_GAP_MS) {
            // 관측 공백이 길면 궤적이 끊긴 것 — 새 기준점에서 정지 확인을 다시 시작한다
            staticSinceMs = nowMs
            return isMoving(nowMs)
        }
        if (abs(zoomRatio - previous.zoomRatio) > ZOOM_EPS) {
            // 줌이 바뀐 구간은 기하가 달라져 비교 불가 — 판정을 건너뛴다 (정지 타이머는 유지)
            return isMoving(nowMs)
        }
        val dtS = dtMs / 1000f
        if (cameraRotating(previous.cameraOrientationRad, cameraOrientationRad, dtS)) {
            staticSinceMs = nowMs
            return isMoving(nowMs)
        }
        val speed = hypot(centerX - previous.centerX, centerY - previous.centerY) / dtS
        val scaleRate = abs(scale - previous.scale) / previous.scale.coerceAtLeast(MIN_SCALE) / dtS
        if (speed >= movingSpeedThreshold || scaleRate >= movingScaleRateThreshold) {
            movingUntilMs = nowMs + movingHoldMs
            staticSinceMs = null
        } else if (staticSinceMs == null) {
            staticSinceMs = nowMs
        }
        return isMoving(nowMs)
    }

    /** 최근 [movingHoldMs] 안에 움직임이 관측됐는가. */
    fun isMoving(nowMs: Long): Boolean = nowMs < movingUntilMs

    /**
     * 줌을 걸어도 되는 "정지" 상태인가 — 움직임 유지 창이 끝났고 [staticConfirmMs] 이상
     * 정지 관측이 이어졌을 때만 true. 관측이 하나도 없으면 false.
     */
    fun isStatic(nowMs: Long): Boolean {
        if (isMoving(nowMs)) return false
        val since = staticSinceMs ?: return false
        return nowMs - since >= staticConfirmMs
    }

    private fun cameraRotating(
        previous: Pair<Float, Float>?,
        current: Pair<Float, Float>?,
        dtS: Float,
    ): Boolean {
        if (previous == null || current == null) return false
        val rate = hypot(current.first - previous.first, current.second - previous.second) / dtS
        return rate >= cameraRotationThresholdRadPerS
    }

    companion object {
        /** 이 속도(프레임 폭/초) 이상 중심이 이동하면 움직임 — 1초에 화면의 1/5. */
        const val MOVING_SPEED_PER_S = 0.20f
        /** 크기(√면적비)가 1초에 이 비율 이상 변하면 움직임(다가옴/멀어짐). */
        const val MOVING_SCALE_RATE_PER_S = 0.35f
        /** 움직임이 한 번 관측되면 이만큼 "움직이는 중"으로 유지한다. */
        const val MOVING_HOLD_MS = 1_000L
        /** 정지 관측이 이만큼 이어져야 줌을 허용한다. */
        const val STATIC_CONFIRM_MS = 600L
        /** 카메라가 이 각속도(rad/s, ≈20°/s) 이상 돌면 피사체 움직임을 판정하지 않는다. */
        const val CAMERA_ROTATION_RAD_PER_S = 0.35f
        /** 이보다 가까운 두 관측은 비교하지 않는다 — 지터를 속도로 부풀리지 않기 위한 최소 간격. */
        const val MIN_JUDGE_DT_MS = 250L
        /** 이보다 긴 관측 공백은 궤적 단절로 본다. */
        const val MAX_GAP_MS = 1_500L
        private const val ZOOM_EPS = 0.02f
        private const val MIN_SCALE = 0.02f
    }
}
