package com.lifeline.mesh.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Everything outside the app that decides whether the mesh can work (CLAUDE.md 7.1).
 * [blocking] problems stop the mesh; the others only degrade it.
 */
data class MeshHealth(
    val missingPermissions: List<String> = emptyList(),
    val bluetoothOn: Boolean = true,
    val hasBluetooth: Boolean = true,
    val locationOn: Boolean = true,
    val wifiOn: Boolean = true,
    /** [ConnectionResult] code from Google Play services. */
    val playServices: Int = ConnectionResult.SUCCESS,
    /**
     * Exempt from battery optimization. Measured on the iQOO 15 (9 Oct): without it the OS freezes the app
     * about 10 s after the screen turns off, foreground service or not, so nothing is relayed.
     */
    val batteryExempt: Boolean = true,
    /** Last error from Nearby itself (advertising or discovery failed), in plain words. */
    val radioError: String? = null,
) {
    val permissionsOk get() = missingPermissions.isEmpty()
    val playServicesOk get() = playServices == ConnectionResult.SUCCESS
    /** Nothing can be sent or received until these are fixed. */
    val blocking get() = !permissionsOk || !playServicesOk || (hasBluetooth && !bluetoothOn && !wifiOn)

    companion object {
        /** Runtime permissions Nearby Connections needs on this Android version (checked Oct 2026). */
        @SuppressLint("InlinedApi")
        fun requiredPermissions(): List<String> {
            val sdk = Build.VERSION.SDK_INT
            return buildList {
                if (sdk >= 31) addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
                if (sdk >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                if (sdk >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
                if (sdk <= 32) add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        fun missingPermissions(context: Context): List<String> = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        fun check(context: Context, radioError: String? = null): MeshHealth {
            val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val loc = context.getSystemService(LocationManager::class.java)
            return MeshHealth(
                missingPermissions = missingPermissions(context),
                hasBluetooth = bt != null,
                bluetoothOn = bt != null && runCatching { bt.isEnabled }.getOrDefault(false),
                locationOn = loc?.let(LocationManagerCompat::isLocationEnabled) ?: false,
                wifiOn = wifi?.isWifiEnabled ?: false,
                playServices = runCatching {
                    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                }.getOrDefault(ConnectionResult.SERVICE_MISSING),
                radioError = radioError,
                batteryExempt = context.getSystemService(PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
            )
        }
    }
}
