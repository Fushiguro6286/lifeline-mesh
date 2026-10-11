package com.lifeline.mesh.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.lifeline.mesh.ai.GeoFix
import com.lifeline.mesh.ai.LocationChoice
import com.lifeline.mesh.ai.QualityGate
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Position for alerts and check-ins via the Fused Location Provider. High accuracy uses GPS, which needs no
 * internet. [QualityGate.chooseLocation] decides exact vs approximate (CLAUDE.md 9.2).
 * Never throws: no permission, location off or no fix all end as "unknown".
 */
class LocationProvider(context: Context) {
    private val app = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(app)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** The phone's last known position, at once. */
        @SuppressLint("MissingPermission")
        suspend fun lastKnown(): GeoFix? {
        if (!hasPermission()) return null
        return try { client.lastLocation.await()?.toFix() } catch (e: Exception) { Log.w(TAG, "lastLocation", e); null }
    }

    /** A fresh fix, waiting at most [timeoutMs] (8 s by default). */
    @SuppressLint("MissingPermission")
    suspend fun fresh(timeoutMs: Long = QualityGate.GPS_WAIT_MS): GeoFix? {
        if (!hasPermission()) return null
        val cancel = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(timeoutMs)
            .setMaxUpdateAgeMillis(0)
            .build()
        return try {
            withTimeoutOrNull(timeoutMs) { client.getCurrentLocation(request, cancel.token).await(cancel)?.toFix() }
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentLocation", e)
            null
        } finally {
            cancel.cancel()
        }
    }

    /**
     * Continuous fixes for the live position (Phase 10), every ~[intervalMs]. Ends quietly (no fixes) without permission.
     * Works with the screen off only while the mesh service runs as a location foreground service.
     */
    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long = 15_000L): kotlinx.coroutines.flow.Flow<GeoFix> = kotlinx.coroutines.flow.callbackFlow {
        if (!hasPermission()) { close(); return@callbackFlow }
        val request = com.google.android.gms.location.LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val callback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                result.lastLocation?.let { trySend(it.toFix()) }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())
        } catch (e: Exception) {
            Log.w(TAG, "requestLocationUpdates", e)
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    /**
     * CLAUDE.md 9.2: fresh within 8 s and 50 m is exact, otherwise last known and approximate. [live] = the mesh
     * service's filtered fix (FixFilter): if it is recent and more accurate than the one-shot fix, it is used.
     */
    suspend fun best(live: GeoFix? = null): LocationChoice =
        QualityGate.chooseLocation(fresh(), lastKnown(), live, System.currentTimeMillis())

    /**
     * [GeoFix.timeMs] on this phone's wall clock: now minus the fix's age from the uptime clock. A GPS fix's own time is
     * satellite time, which can differ from the phone's clock, so a fresh fix could look old or "in the future".
     */
    private fun Location.toFix(): GeoFix {
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L
        val at = if (elapsedRealtimeNanos > 0 && ageMs >= 0) System.currentTimeMillis() - ageMs else time
        return GeoFix(latitude, longitude, if (hasAccuracy()) accuracy else null, at)
    }

    companion object {
        private const val TAG = "LocationProvider"
    }
}
