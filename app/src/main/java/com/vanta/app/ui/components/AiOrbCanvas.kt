package com.vanta.app.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A sleek 3D particle sphere canvas directly driven by device hardware gyroscope & rotation sensors.
 * Reacts to physical device tilt and angular motion with smooth physical dampening.
 */
@Composable
fun AiOrbCanvas(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    particleColor: Color = Color(0xFFFF7A45),
    pointCount: Int = 110
) {
    val context = LocalContext.current

    // Device gyro rotation states
    var gyroAngleX by remember { mutableFloatStateOf(0f) }
    var gyroAngleY by remember { mutableFloatStateOf(0f) }

    // Subtle idle pulse for breathing feel
    val transition = rememberInfiniteTransition(label = "orb_idle")
    val idlePulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Hardware sensor connection
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val gyroSensor = if (rotationSensor == null) sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) else null

        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientationValues = FloatArray(3)
            private var lastTimestamp: Long = 0

            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return

                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationValues)
                    // orientationValues: [0] = azimuth/yaw, [1] = pitch (tilt X), [2] = roll (tilt Y)
                    val pitch = orientationValues[1]
                    val roll = orientationValues[2]

                    // Smooth interpolation
                    gyroAngleX = gyroAngleX * 0.85f + pitch * 1.5f * 0.15f
                    gyroAngleY = gyroAngleY * 0.85f + roll * 1.8f * 0.15f
                } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    val currentTimestamp = event.timestamp
                    if (lastTimestamp != 0L) {
                        val dt = (currentTimestamp - lastTimestamp) * 1.0e-9f
                        val radX = event.values[0]
                        val radY = event.values[1]
                        gyroAngleX += radX * dt * 2.2f
                        gyroAngleY += radY * dt * 2.2f
                    }
                    lastTimestamp = currentTimestamp
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (rotationSensor != null) {
            sensorManager?.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (gyroSensor != null) {
            sensorManager?.registerListener(listener, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        }

        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    // Precalculate Fibonacci sphere points
    val basePoints = remember(pointCount) {
        val points = mutableListOf<Triple<Float, Float, Float>>()
        val phi = Math.PI * (sqrt(5.0) - 1.0) // Golden angle
        for (i in 0 until pointCount) {
            val y = 1f - (i / (pointCount - 1f)) * 2f // y goes from 1 to -1
            val radius = sqrt(1f - y * y)
            val theta = phi * i
            val x = (cos(theta) * radius).toFloat()
            val z = (sin(theta) * radius).toFloat()
            points.add(Triple(x, y, z))
        }
        points
    }

    Canvas(modifier = modifier.size(size)) {
        val centerX = this.size.width / 2f
        val centerY = this.size.height / 2f
        val sphereRadius = (this.size.minDimension / 2f) * 0.80f * idlePulse

        val cosY = cos(gyroAngleY)
        val sinY = sin(gyroAngleY)
        val cosX = cos(gyroAngleX)
        val sinX = sin(gyroAngleX)

        basePoints.forEach { (bx, by, bz) ->
            // Rotate around Y (roll / horizontal tilt)
            val x1 = bx * cosY + bz * sinY
            val z1 = -bx * sinY + bz * cosY

            // Rotate around X (pitch / vertical tilt)
            val y2 = by * cosX - z1 * sinX
            val z2 = by * sinX + z1 * cosX

            // Perspective scale (z2 ranges from -1 to 1)
            val px = centerX + x1 * sphereRadius
            val py = centerY + y2 * sphereRadius

            val alpha = ((z2 + 1f) / 2f).coerceIn(0.18f, 0.95f)
            val dotRadius = (1.1f + (z2 + 1f) * 0.85f).coerceIn(0.8f, 2.8f)

            drawCircle(
                color = particleColor.copy(alpha = alpha),
                radius = dotRadius,
                center = Offset(px, py)
            )
        }
    }
}
