package com.wim.app

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log
import kotlin.math.sqrt

/**
 * Listens for a deliberate double-shake of the device and fires [onDoubleShake]
 * on the calling thread (Android dispatches accelerometer events on the main
 * looper by default).
 *
 * Extracted from BubbleService 2026-05-25 as the first slice of the
 * god-object split flagged by the architecture review. Pure behavior — no
 * UI references — so the bubble service can stay responsible for what to
 * do on a shake while this class owns when to fire.
 *
 * Rising-edge detection: one sustained burst of high gForce counts as one
 * shake regardless of how many oscillation peaks it contains. Time-based
 * debounce kept refreshing on every internal peak, so [shakeCount] never
 * advanced past 1.
 *
 * Asymmetric thresholds — first shake hard enough to prevent accidents
 * (set-down jolt, pocket movement), follow-up easier. Release threshold
 * sits below resting gravity (~1.0) so a brief pause between shakes
 * registers reliably.
 */
class BubbleShakeDetector(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val onDoubleShake: () -> Unit
) {
    private companion object {
        private const val TAG = "WiM"
        private const val SHAKE_MIN_QUIET_MS = 280L
    }

    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var lastShakeTime = 0L
    private var shakeCount = 0
    private var inShakeBurst = false
    private var shakeBurstEndTime = 0L

    fun start() {
        sensorManager = context.getSystemService(SensorManager::class.java)
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            Log.w(TAG, "shake: no accelerometer sensor available")
            return
        }
        Log.i(TAG, "shake: setupShakeDetector starting, accelerometer=${accelerometer.name}")

        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val powerManager = context.getSystemService(PowerManager::class.java)
                val keyguardManager = context.getSystemService(KeyguardManager::class.java)
                if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) return

                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val gForce = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH

                if (!prefs.getBoolean("shake_enabled", true)) return

                val highThreshold = if (shakeCount == 0) 2.6 else 2.0
                val releaseThreshold = 1.5
                if (gForce > 1.4) Log.i(
                    TAG,
                    "shake gForce=${"%.2f".format(gForce)} count=$shakeCount " +
                        "inBurst=$inShakeBurst high=$highThreshold"
                )

                val now = System.currentTimeMillis()

                if (gForce > highThreshold && !inShakeBurst) {
                    if (shakeBurstEndTime > 0 && now - shakeBurstEndTime < SHAKE_MIN_QUIET_MS) {
                        inShakeBurst = true
                        return
                    }
                    inShakeBurst = true
                    if (now - lastShakeTime > 1500) {
                        shakeCount = 1
                    } else {
                        shakeCount++
                    }
                    lastShakeTime = now
                    Log.i(TAG, "shake EDGE shakeCount→$shakeCount")

                    if (shakeCount >= 2) {
                        shakeCount = 0
                        onDoubleShake()
                    }
                } else if (gForce < releaseThreshold && inShakeBurst) {
                    inShakeBurst = false
                    shakeBurstEndTime = now
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager?.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(listener)
        listener = null
    }
}
