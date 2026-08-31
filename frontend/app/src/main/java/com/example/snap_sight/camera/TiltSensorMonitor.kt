// 이 파일: 폰이 앞뒤·좌우로 얼마나 기울었는지 센서로 재는 도구.
// 조준 중에만 켜져서 배터리를 아끼고,
// "폰을 수평으로 들어주세요" 같은 안내에 쓸 기울기 값을 제공한다.
package com.example.snap_sight.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 가속도계 기반 기울기 측정 (기술 스택: "노출·기울기 — IMU(가속도계)").
 *
 * 세로 파지 기준:
 *  - [rollDegrees]  좌우 기울기. 0° = 수평. 부호 (실기기 확정 2026-08-28): 폰을 **왼쪽(반시계)
 *    으로 돌리면 +**, 오른쪽(시계)으로 돌리면 −. 가로 파지(±90°)도 정상 자세이므로 소비자는
 *    절대값이 아니라 [PhoneRoll.deviationFromNearestSnap] 편차를 쓴다. 소비자: 풍경 안내
 *    ([com.example.snap_sight.ux.LandscapeGuide]), 인물·사물 세션 수평 안내
 *    ([com.example.snap_sight.ux.GuidancePolicy]), 저장 시 수평 보정([HorizonStraightener]).
 *  - [pitchDegrees] 앞뒤 기울기. 0° = 폰이 지면과 수직(카메라가 정면). 양수 = 하늘 쪽
 *
 * 값은 저역 통과 필터로 손떨림을 걸러낸 뒤, 0.5° 이상 변할 때만 리스너에 전달한다.
 * ③(판정)·⑥(피드백)이 조준 루프에서 소비한다.
 */
class TiltSensorMonitor(context: Context) : SensorEventListener {

    fun interface Listener {
        /** 메인 스레드가 아닐 수 있음 (센서 스레드). UI 반영 시 주의. */
        fun onTiltChanged(rollDegrees: Float, pitchDegrees: Float)
    }

    @Volatile
    var listener: Listener? = null
        set(value) {
            field = value
            // 소비자가 사라졌는데 센서만 계속 샘플링하는 상태를 허용하지 않는다.
            if (value == null) stop()
        }

    @Volatile
    var rollDegrees: Float = 0f
        private set

    @Volatile
    var pitchDegrees: Float = 0f
        private set

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gravity = FloatArray(3)
    private var initialized = false
    @Volatile
    private var running = false

    /** 조준 루프(AIMING) 진입 시 호출. 센서가 없으면 false. */
    fun start(): Boolean {
        // tilt 값을 소비하지 않는 구성에서는 센서 등록 비용이 0이어야 한다.
        if (listener == null) return false
        val sensor = accelerometer ?: return false
        if (running) return true
        running = true
        initialized = false
        val registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        if (!registered) running = false
        return registered
    }

    /** 조준 루프 이탈 시 호출. */
    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        // 저역 통과: gravity = α·gravity + (1-α)·측정값
        if (!initialized) {
            event.values.copyInto(gravity, endIndex = 3)
            initialized = true
        } else {
            for (i in 0..2) {
                gravity[i] = ALPHA * gravity[i] + (1 - ALPHA) * event.values[i]
            }
        }

        val (x, y, z) = gravity
        val roll = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        val pitch = Math.toDegrees(
            atan2(z.toDouble(), sqrt((x * x + y * y).toDouble()))
        ).toFloat()

        if (abs(roll - rollDegrees) >= MIN_DELTA_DEG || abs(pitch - pitchDegrees) >= MIN_DELTA_DEG) {
            rollDegrees = roll
            pitchDegrees = pitch
            listener?.onTiltChanged(roll, pitch)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]
    private operator fun FloatArray.component3() = this[2]

    private companion object {
        const val ALPHA = 0.8f
        const val MIN_DELTA_DEG = 0.5f
    }
}
