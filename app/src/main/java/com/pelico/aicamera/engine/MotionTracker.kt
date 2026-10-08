package com.pelico.aicamera.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 传感器辅助：
 * - 陀螺仪判断手是否端稳（用于提示"别抖"）
 * - 旋转向量给出机身 roll，用来做水平仪
 *
 * 这一版不再驱动任何构图框，只提供确定性的参考信息，所以不存在框抖动的问题。
 */
class MotionTracker(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile
    private var angularSpeed = 0f

    @Volatile
    private var roll = 0f

    fun start() {
        gyro?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        runCatching { sensorManager.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z)
                angularSpeed = angularSpeed * 0.8f + magnitude * 0.2f
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    val isSteady: Boolean get() = angularSpeed < STEADY_THRESHOLD

    /** 机身横滚角（度），用于水平仪 */
    val tiltDeg: Float get() = roll

    val isLevel: Boolean get() = abs(roll) < LEVEL_THRESHOLD_DEG

    companion object {
        private const val STEADY_THRESHOLD = 0.35f
        private const val LEVEL_THRESHOLD_DEG = 3f
    }
}
