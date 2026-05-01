package com.gyrowallpaper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class GyroscopeManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Normalized tilt: -1.0 to 1.0 on each axis
    @Volatile var tiltX = 0f   // left / right
    @Volatile var tiltY = 0f   // forward / back

    private var smoothX = 0f
    private var smoothY = 0f
    private val alpha = 0.08f  // low-pass smoothing

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: accelSensor?.let {
            // Fallback to accelerometer if no rotation vector
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event)
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
        }
    }

    private fun handleRotationVector(event: SensorEvent) {
        val rot = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rot, event.values)

        // Remap for portrait mode (phone held upright)
        val remapped = FloatArray(9)
        SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)

        val orientation = FloatArray(3)
        SensorManager.getOrientation(remapped, orientation)

        // orientation[2] = roll (left/right tilt), orientation[1] = pitch (fwd/back tilt)
        val targetX = (orientation[2] / (Math.PI / 3.0)).toFloat().coerceIn(-1f, 1f)
        val targetY = (orientation[1] / (Math.PI / 4.0)).toFloat().coerceIn(-1f, 1f)

        smoothX += alpha * (targetX - smoothX)
        smoothY += alpha * (targetY - smoothY)

        tiltX = smoothX
        tiltY = smoothY
    }

    private fun handleAccelerometer(event: SensorEvent) {
        // Fallback: use raw gravity when no rotation vector available
        val targetX = (-event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        val targetY = ((event.values[1] + SensorManager.GRAVITY_EARTH) / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)

        smoothX += alpha * (targetX - smoothX)
        smoothY += alpha * (targetY - smoothY)

        tiltX = smoothX
        tiltY = smoothY
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
