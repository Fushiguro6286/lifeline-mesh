package com.lifeline.mesh.ai

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Keeps AI from hurting the phone (docs/MODEL_SELECTOR.md section 5): pauses non-essential AI (situation report, ask,
 * translate) when the phone is SEVERE-hot or in power save under 20% battery, and asks for the model to be unloaded
 * on critical memory pressure. Extraction keeps running unless memory is critical.
 */
class AiGovernor(private val context: Context) {
    data class State(val thermalSevere: Boolean = false, val lowBatteryPowerSave: Boolean = false, val memoryCritical: Boolean = false) {
        val pauseNonEssential get() = thermalSevere || lowBatteryPowerSave || memoryCritical
        val reason: String? get() = when {
            memoryCritical -> "low memory"
            thermalSevere -> "cooling down"
            lowBatteryPowerSave -> "battery saver"
            else -> null
        }
    }

    /**
     * Low-battery survival mode (Settings, on by default): under 20 % and not charging, non-essential AI pauses even
     * without the system battery saver.
     */
    @Volatile var survival: Boolean = true
        set(v) { if (field != v) { field = v; if (started) refreshBattery() } }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var started = false

    fun start() {
        if (started) return
        started = true
        val pm = context.getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 29) {
            _state.update { it.copy(thermalSevere = pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) }
            pm.addThermalStatusListener(ContextCompat.getMainExecutor(context)) { status ->
                // Pause at SEVERE, resume at MODERATE or better.
                _state.update { s ->
                    when {
                        status >= PowerManager.THERMAL_STATUS_SEVERE -> s.copy(thermalSevere = true)
                        status <= PowerManager.THERMAL_STATUS_MODERATE -> s.copy(thermalSevere = false)
                        else -> s
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refreshBattery()
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshBattery()
    }

    private fun refreshBattery() {
        val pm = context.getSystemService(PowerManager::class.java)
        val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val s = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (l >= 0 && s > 0) l * 100 / s else 100
        val charging = (b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }
        _state.update { it.copy(lowBatteryPowerSave = pct < 20 && !charging && (pm.isPowerSaveMode || survival)) }
    }

    /** From Application.onTrimMemory. Returns true when the model should be unloaded now. */
    fun onTrimMemory(level: Int): Boolean {
        @Suppress("DEPRECATION")
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (critical) _state.update { it.copy(memoryCritical = true) }
        return critical
    }

    /** Back in the foreground with memory to spare: allow reloading. */
    fun memoryRecovered() = _state.update { it.copy(memoryCritical = false) }
}
