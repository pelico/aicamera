package com.pelico.aicamera.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * 陀螺仪辅助：判断手机是否端稳，并据此调整构图框的平滑强度。
 * 手抖时把平滑系数压小（框更稳但响应慢），端稳时响应更快。
 */
class MotionTracker(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile
    private var angularSpeed = 0f

    fun start() {
        gyro?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        runCatching { sensorManager.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        angularSpeed = angularSpeed * 0.8f + magnitude * 0.2f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    val isSteady: Boolean get() = angularSpeed < STEADY_THRESHOLD

    val speed: Float get() = angularSpeed

    /** 手抖时用更小的 alpha，让框不至于乱跳 */
    fun smoothingAlpha(base: Float): Float =
        if (isSteady) base else (base * 0.45f).coerceAtLeast(0.06f)

    companion object {
        private const val STEADY_THRESHOLD = 0.35f
    }
}
