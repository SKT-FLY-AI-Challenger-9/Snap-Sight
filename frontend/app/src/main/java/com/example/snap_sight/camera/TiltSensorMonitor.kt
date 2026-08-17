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
 *  - [rollDegrees]  좌우 기울기. 0° = 수평. 양수 = 시계방향으로 기울어짐 → 수평 맞추기 판정용
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

    var listener: Listener? = null

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
    private var running = false

    /** 조준 루프(AIMING) 진입 시 호출. 센서가 없으면 false. */
    fun start(): Boolean {
        val sensor = accelerometer ?: return false
        if (running) return true
        running = true
        initialized = false
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        return true
    }

    /** 조준 루프 이탈 시 호출. */
    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
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
