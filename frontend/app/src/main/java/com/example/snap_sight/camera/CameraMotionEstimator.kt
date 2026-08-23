// 이 파일: 자이로스코프로 "프레임 사이에 카메라가 얼마나 돌았는지"를 재서
// 트래커의 예측 위치를 보정할 모션 힌트(MotionHint)를 만드는 도구.
// 화면을 못 보는 사용자가 카메라를 크게 휘두르는 앱 특성상 IoU 매칭이 끊기는 주원인을 상쇄한다.
// docs/feature-expansion-plan.md 기능 1-C.
package com.example.snap_sight.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.snap_sight.cv.MotionHint

/**
 * 자이로 각속도를 적분해, 마지막 [consumeHint] 이후의 화면 내 이동량(normalized)을 돌려준다.
 *
 * 좌표 가정 (세로 파지 + upright 분석 프레임 기준):
 *  - 오른쪽으로 패닝(ω_y < 0) → 화면 속 객체는 왼쪽으로 이동 → dx < 0
 *  - 위로 틸트(ω_x > 0) → 객체는 아래로 이동 → dy > 0
 *  - 회전량 → 화면 이동량 환산은 시야각(FOV) 비례 근사: dx = Δyaw / FOV_h
 *
 * FOV 는 기기별 실측 대신 일반적인 스마트폰 후면 카메라 값을 상수로 쓴다 — 보정은
 * "매칭이 끊기지 않을 정도"면 충분하고, 오차는 IoU 임계값 여유로 흡수된다.
 *
 * ⚠️ [ENABLED] 로 꺼져 있다 — 부호·축 가정을 실기기에서 검증한 뒤 켠다
 * (AutoZoomController.ZOOM_IN_ENABLED 와 같은 패턴). 검증 방법: 조준 중 카메라를
 * 좌우로 흔들며 `SnapSightCV` 로그의 track_id 유지율이 좋아지는지 비교.
 *
 * 스레딩: 센서 콜백(센서 스레드)이 적분하고, CV 분석 스레드가 [consumeHint] 로 소비한다.
 */
class CameraMotionEstimator(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val lock = Any()
    private var accumulatedYawRad = 0.0   // 좌우 패닝 (device y축)
    private var accumulatedPitchRad = 0.0 // 상하 틸트 (device x축)
    private var lastEventTimestampNs = 0L
    private var running = false

    /** 조준(AIMING) 진입 시 호출. 자이로가 없으면 false — 힌트는 항상 null 이 된다. */
    fun start(): Boolean {
        // 기능 플래그가 꺼진 빌드에서는 센서 리스너도 실제로 등록하지 않는다.
        if (!ENABLED) return false
        val sensor = gyroscope ?: return false
        synchronized(lock) {
            if (running) return true
            running = true
            accumulatedYawRad = 0.0
            accumulatedPitchRad = 0.0
            lastEventTimestampNs = 0L
        }
        val registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        if (!registered) synchronized(lock) { running = false }
        return registered
    }

    /** 조준 이탈 시 호출. */
    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
        }
        sensorManager.unregisterListener(this)
    }

    /**
     * 마지막 호출 이후 누적된 이동량을 소비한다. 분석 프레임마다 1회 호출할 것.
     * 꺼져 있거나([ENABLED]=false) 자이로가 없거나 이동이 미미하면 null.
     */
    fun consumeHint(): MotionHint? {
        if (!ENABLED) return null
        val (yaw, pitch) = synchronized(lock) {
            if (!running) return null
            val values = accumulatedYawRad to accumulatedPitchRad
            accumulatedYawRad = 0.0
            accumulatedPitchRad = 0.0
            values
        }
        val dx = (yaw / HORIZONTAL_FOV_RAD).toFloat()
        val dy = (pitch / VERTICAL_FOV_RAD).toFloat()
        if (kotlin.math.abs(dx) < MIN_HINT && kotlin.math.abs(dy) < MIN_HINT) return null
        return MotionHint(dx = dx.coerceIn(-1f, 1f), dy = dy.coerceIn(-1f, 1f))
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(lock) {
            if (!running) return
            val previous = lastEventTimestampNs
            lastEventTimestampNs = event.timestamp
            if (previous == 0L) return
            val dtS = (event.timestamp - previous) / 1_000_000_000.0
            if (dtS <= 0.0 || dtS > MAX_SANE_DT_S) return
            // ω_y(패닝): 오른쪽 회전이 음수 → dx 음수(객체가 왼쪽으로) — KDoc 부호 가정 참고
            accumulatedYawRad += event.values[1] * dtS
            accumulatedPitchRad += event.values[0] * dtS
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /**
         * 부호·축 가정을 실기기에서 검증하기 전까지 OFF.
         * 검증 후 true 로 바꾸면 MainActivity 배선이 그대로 살아난다.
         */
        const val ENABLED = false

        // 일반적인 폰 후면 카메라 시야각 (세로 파지 upright 프레임 기준 근사)
        private const val HORIZONTAL_FOV_RAD = 66.0 * Math.PI / 180.0
        private const val VERTICAL_FOV_RAD = 52.0 * Math.PI / 180.0

        /** 이보다 작은 이동은 노이즈로 보고 힌트를 만들지 않는다 (normalized). */
        private const val MIN_HINT = 0.002f

        /** 센서 이벤트 간격 상식선 — 앱 정지 후 재개 등으로 생긴 비정상 간격은 버린다. */
        private const val MAX_SANE_DT_S = 0.5
    }
}
