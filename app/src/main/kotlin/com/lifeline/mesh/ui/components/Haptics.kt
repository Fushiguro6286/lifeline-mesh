package com.lifeline.mesh.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibration that is strong enough to feel through a pocket, which Compose haptics are not. */
object Haptics {
    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** Short tick, used once per second while holding the SOS button. */
    fun tick(context: Context) {
        val v = vibrator(context) ?: return
        if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        else v.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Two long full-strength pulses: SOS sent, or a reply arrived. */
    fun strong(context: Context) {
        val v = vibrator(context) ?: return
        val timings = longArrayOf(0, 300, 150, 300)
        val amplitudes = intArrayOf(0, 255, 0, 255)
        v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }
}
