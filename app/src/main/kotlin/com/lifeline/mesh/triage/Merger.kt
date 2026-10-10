package com.lifeline.mesh.triage

import com.lifeline.mesh.packet.Need
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One stored alert, as the rescuer side needs it. Pure Kotlin. [lat]/[lon] null = position unknown. */
data class AlertReport(
    val id: Long,
    val origin: Int,
    val lat: Double?,
    val lon: Double?,
    /** Metres, null if unknown. */
    val accuracyM: Int?,
    val needs: Set<Need>,
    val severity: Int,
    val people: Int,
    val note: String,
    /** Wall-clock ms when this phone stored it. */
    val receivedAt: Long,
    /** Links travelled to reach this phone. */
    val hops: Int,
    val acked: Boolean,
    val ackEta: Int?,
    /** Reached this phone from the sync server, not over the mesh (Phase 10 internet relay). */
    val viaInternet: Boolean = false,
)

/** Several reports of what is most likely the same emergency (CLAUDE.md 5: Merger → Ranker). */
data class MergedAlert(val reports: List<AlertReport>) {
    init { require(reports.isNotEmpty()) }

    /** The newest report speaks for the group (its ID is the group's key). */
    val lead: AlertReport get() = reports.maxBy { it.receivedAt }
    val id: Long get() = reports.minBy { it.receivedAt }.id
    val needs: Set<Need> get() = lead.needs
    val severity: Int get() = reports.maxOf { it.severity }
    /** Reports of one place usually describe the same people: take the largest count, don't add. */
    val people: Int get() = reports.maxOf { it.people }
    val firstAt: Long get() = reports.minOf { it.receivedAt }
    val lastAt: Long get() = reports.maxOf { it.receivedAt }
    /** The most accurate known position. */
    val position: AlertReport? get() = reports.filter { it.lat != null && it.lon != null }.minByOrNull { it.accuracyM ?: Int.MAX_VALUE }
    val note: String get() = reports.sortedByDescending { it.receivedAt }.firstOrNull { it.note.isNotBlank() }?.note ?: ""
    /** Acknowledged once any of its reports got a "help is coming". */
    val acked: Boolean get() = reports.any { it.acked }
    val ackEta: Int? get() = reports.filter { it.acked }.mapNotNull { it.ackEta }.minOrNull()
    val count: Int get() = reports.size
    /** Every copy came from the server: nobody nearby relayed it. */
    val viaInternet: Boolean get() = reports.all { it.viaInternet }

    /**
     * Done: a phone that sent one of these reports checked in safe ("Are you safe?" → yes) after the newest report.
     * [checkIns] = (origin, time stored here). A newer report of the same emergency opens it again.
     */
    fun closedBy(checkIns: List<Pair<Int, Long>>): Boolean {
        val origins = reports.map { it.origin }.toSet()
        return checkIns.any { (origin, at) -> origin in origins && at > lastAt }
    }
}

/**
 * Merges duplicate reports (CLAUDE.md 12, Phase 5): same needs, within [radiusM] (60 m) and [windowMs] (15 min) of a
 * report already in the group. Reports without a position merge only with reports from the same phone, since two
 * unknown positions say nothing about being in the same place. Deterministic: same input, same groups.
 */
object Merger {
    const val RADIUS_M = 60.0
    const val WINDOW_MS = 15 * 60 * 1000L

    fun merge(reports: List<AlertReport>, radiusM: Double = RADIUS_M, windowMs: Long = WINDOW_MS): List<MergedAlert> {
        val groups = ArrayList<MutableList<AlertReport>>()
        for (r in reports.sortedWith(compareBy<AlertReport> { it.receivedAt }.thenBy { it.id })) {
            val home = groups.firstOrNull { g -> g.any { sameEmergency(it, r, radiusM, windowMs) } }
            if (home != null) home.add(r) else groups.add(mutableListOf(r))
        }
        return groups.map { MergedAlert(it) }
    }

    fun sameEmergency(a: AlertReport, b: AlertReport, radiusM: Double = RADIUS_M, windowMs: Long = WINDOW_MS): Boolean {
        if (a.needs != b.needs) return false
        if (kotlin.math.abs(a.receivedAt - b.receivedAt) > windowMs) return false
        val pa = a.lat != null && a.lon != null
        val pb = b.lat != null && b.lon != null
        return if (pa && pb) GeoMath.distanceM(a.lat!!, a.lon!!, b.lat!!, b.lon!!) <= radiusM else a.origin == b.origin
    }
}

object GeoMath {
    private const val EARTH_M = 6_371_000.0

    /** Great-circle distance in metres (haversine). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from north, 0–360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** "N", "NE", … for a bearing. */
    fun compass(bearing: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((bearing + 22.5) % 360 / 45).toInt()]
}
