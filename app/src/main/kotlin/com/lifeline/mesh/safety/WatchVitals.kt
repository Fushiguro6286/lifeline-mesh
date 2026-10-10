package com.lifeline.mesh.safety

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/**
 * Reads what a smartwatch synced into Health Connect (10 Oct 2026, on request): blood oxygen, blood pressure, heart rate.
 * The watch's own app (Samsung Health, Fitbit, Pixel Watch, ...) must write to Health Connect; data often arrives
 * minutes late, so this is not a real-time monitor. Read only, never written.
 */
class WatchVitals(context: Context) {
    private val app = context.applicationContext

    enum class Status { UNAVAILABLE, UPDATE_NEEDED, NEEDS_PERMISSION, READY }

    private fun client(): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(app) == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(app) else null

    suspend fun status(): Status = when (HealthConnectClient.getSdkStatus(app)) {
        HealthConnectClient.SDK_AVAILABLE -> {
            val granted = runCatching { client()!!.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
            if (granted.containsAll(READ)) Status.READY else Status.NEEDS_PERMISSION
        }
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Status.UPDATE_NEEDED
        else -> Status.UNAVAILABLE
    }

    /** The permissions to ask for: the three readings, plus reading in the background where Health Connect offers it. */
    suspend fun permissionsToRequest(): Set<String> {
        val c = client() ?: return READ
        val background = runCatching {
            c.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)
        return if (background) READ + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND else READ
    }

    /** Readings after [sinceMs]; empty when Health Connect or a permission is missing. */
    suspend fun read(sinceMs: Long): List<VitalReading> {
        val c = client() ?: return emptyList()
        val range = TimeRangeFilter.after(Instant.ofEpochMilli(sinceMs))
        val out = mutableListOf<VitalReading>()
        runCatching {
            c.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range)).records.forEach {
                out += VitalReading(VitalReading.Kind.OXYGEN, it.percentage.value, it.time.toEpochMilli())
            }
        }.onFailure { Log.w(TAG, "oxygen: ${it.javaClass.simpleName}") }
        runCatching {
            c.readRecords(ReadRecordsRequest(BloodPressureRecord::class, range)).records.forEach {
                out += VitalReading(VitalReading.Kind.BLOOD_PRESSURE, it.systolic.inMillimetersOfMercury, it.time.toEpochMilli(),
                    it.diastolic.inMillimetersOfMercury)
            }
        }.onFailure { Log.w(TAG, "blood pressure: ${it.javaClass.simpleName}") }
        runCatching {
            c.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records.forEach { r ->
                r.samples.forEach { out += VitalReading(VitalReading.Kind.HEART_RATE, it.beatsPerMinute.toDouble(), it.time.toEpochMilli()) }
            }
        }.onFailure { Log.w(TAG, "heart rate: ${it.javaClass.simpleName}") }
        return out
    }

    companion object {
        private const val TAG = "WatchVitals"
        val READ = setOf(
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
        )
    }
}
