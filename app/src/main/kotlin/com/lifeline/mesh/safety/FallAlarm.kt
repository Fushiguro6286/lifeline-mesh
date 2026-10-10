package com.lifeline.mesh.safety

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.sqrt

/**
 * A running alarm: started at [startedAt] (wall ms); [test] = started from a "Try the ... alarm" row. [vitals] = the watch
 * reading that started it (watch vitals alarm, which ends in an ambulance request); null = a fall (ends in a panic SOS).
 */
data class FallAlarm(val startedAt: Long, val test: Boolean, val vitals: String? = null) {
    /** Whole seconds left before the SOS goes out (0 = send now). */
    fun secondsLeft(now: Long): Int = ((COUNTDOWN_MS - (now - startedAt) + 999) / 1000).toInt().coerceAtLeast(0)

    companion object {
        const val COUNTDOWN_MS = 15_000L
    }
}

/**
 * The accelerometer, fed into [FallDetector]. Runs inside the mesh foreground service (which holds a partial wake lock),
 * so it keeps listening with the screen off. About 50 samples a second; the accelerometer needs no permission.
 */
class FallSensor(context: Context, private val onFall: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val detector = FallDetector()
    private var running = false

    /** False if the phone has no accelerometer. */
    fun start(): Boolean {
        if (running) return true
        val acc = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        detector.reset()
        running = sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
        return running
    }

    fun stop() {
        if (!running) return
        sensors?.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
        if (detector.onSample(sqrt((x * x + y * y + z * z).toDouble()), e.timestamp / 1_000_000)) onFall()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/** The alarm itself: the alarm ringtone on a loop and strong repeating vibration, until [stop]. */
class AlarmEffects(private val context: Context) {
    private var ringtone: Ringtone? = null

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    fun start() {
        if (ringtone != null) return
        ringtone = runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }.getOrNull()
        // Marked as an alarm (Android 13+) so the countdown also buzzes on a phone left on silent (11 Oct 2026).
        val loop = VibrationEffect.createWaveform(longArrayOf(0, 700, 300), intArrayOf(0, 255, 0), 0)
        if (Build.VERSION.SDK_INT >= 33) vibrator()?.vibrate(loop, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
        else vibrator()?.vibrate(loop)
        vibrating = true
    }

    private var vibrating = false

    /**
     * Stops the ringtone and the alarm vibration. Only cancels vibration if the alarm is still running: a second stop
     * (when the alarm state clears after the SOS went out) cancelled the "SOS sent" buzz after 6 ms (iQOO 15).
     */
    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        if (vibrating) { vibrating = false; vibrator()?.cancel() }
    }
}
