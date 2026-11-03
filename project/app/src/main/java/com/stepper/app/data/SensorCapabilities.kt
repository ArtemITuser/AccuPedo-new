package com.stepper.app.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager

/**
 * Runtime-проверка возможностей конкретного телефона.
 * Наличие акселерометра ещё не означает наличие аппаратного счётчика шагов:
 * Android допускает отсутствие TYPE_STEP_COUNTER/TYPE_STEP_DETECTOR.
 */
data class SensorCapabilities(
    val stepCounter: Boolean,
    val stepDetector: Boolean,
    val accelerometer: Boolean
) {
    val preferredContinuousSource: String?
        get() = when {
            stepCounter -> "TYPE_STEP_COUNTER"
            stepDetector -> "TYPE_STEP_DETECTOR"
            else -> null
        }

    val canCountContinuously: Boolean
        get() = stepCounter || stepDetector

    companion object {
        fun detect(context: Context): SensorCapabilities {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            return SensorCapabilities(
                stepCounter = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null,
                stepDetector = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null,
                accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
            )
        }
    }
}
