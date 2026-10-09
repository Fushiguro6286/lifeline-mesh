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

    /** CLAUDE.md 9.2: fresh within 8 s and 50 m is exact, otherwise last known and approximate. */
    suspend fun best(): LocationChoice = QualityGate.chooseLocation(fresh(), lastKnown())

    private fun Location.toFix() = GeoFix(latitude, longitude, if (hasAccuracy()) accuracy else null, time)

    companion object {
        private const val TAG = "LocationProvider"
    }
}
