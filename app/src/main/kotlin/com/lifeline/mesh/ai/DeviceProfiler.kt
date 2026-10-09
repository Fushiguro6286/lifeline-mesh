package com.lifeline.mesh.ai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs

/** What the On-device AI card shows about the phone, plus the [DeviceProfile] the decision logic uses. */
data class DeviceInfo(
    val profile: DeviceProfile,
    val phone: String,
    val chip: String,
    val glEs: String,
    val vulkan: Boolean,
    val thermalStatus: Int,
    val batteryPct: Int,
    val charging: Boolean,
    val powerSave: Boolean,
    val lowRamDevice: Boolean,
)

/** Reads the phone (docs/MODEL_SELECTOR.md section 3). Cheap; no model loaded. */
object DeviceProfiler {
    fun read(context: Context): DeviceInfo {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val modelsDir = context.getExternalFilesDir(null) ?: context.filesDir
        val freeMb = runCatching { StatFs(modelsDir.path).availableBytes / 1_000_000 }.getOrDefault(0)
        val pm = context.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else 0
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { b ->
            val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100 / s else -1
        } ?: -1
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val powerSave = pm.isPowerSaveMode
        val chip = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else Build.HARDWARE
        val gl = am.deviceConfigurationInfo.glEsVersion ?: "?"
        val vulkan = if (Build.VERSION.SDK_INT >= 24) context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) else false
        val phone = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val profile = DeviceProfile(
            totalRamMb = mem.totalMem / 1_000_000,
            availRamMb = mem.availMem / 1_000_000,
            freeStorageMb = freeMb,
            isArm64 = "arm64-v8a" in Build.SUPPORTED_ABIS,
            sdkInt = Build.VERSION.SDK_INT,
            socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null,
            thermalSevere = Build.VERSION.SDK_INT >= 29 && thermal >= PowerManager.THERMAL_STATUS_SEVERE,
            powerSaveLowBattery = powerSave && level in 0..19,
            phone = phone,
        )
        return DeviceInfo(profile, phone, chip, gl, vulkan, thermal, level, charging, powerSave, am.isLowRamDevice)
    }
}
