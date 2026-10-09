package com.lifeline.mesh.triage

/** A merged alert with its place in the queue and where it is from the rescuer. */
data class RankedAlert(
    val rank: Int,
    val alert: MergedAlert,
    /** Metres from the rescuer, null when either position is unknown. */
    val distanceM: Double?,
    /** Degrees from north, null when unknown. */
    val bearingDeg: Double?,
)

/**
 * Orders merged alerts for the rescuer (CLAUDE.md 12, Phase 5): severity (highest first), then number of reports
 * (more people saw it), then distance (nearest first; unknown distance last), then oldest first so a tie never
 * reshuffles. Deterministic Kotlin, no model.
 */
object Ranker {
    fun rank(alerts: List<MergedAlert>, myLat: Double?, myLon: Double?): List<RankedAlert> {
        val withDistance = alerts.map { a ->
            val p = a.position
            if (p == null || myLat == null || myLon == null) Triple(a, null, null)
            else Triple(a, GeoMath.distanceM(myLat, myLon, p.lat!!, p.lon!!), GeoMath.bearingDeg(myLat, myLon, p.lat, p.lon))
        }
        return withDistance
            .sortedWith(
                compareByDescending<Triple<MergedAlert, Double?, Double?>> { it.first.severity }
                    .thenByDescending { it.first.count }
                    .thenBy { it.second ?: Double.MAX_VALUE }
                    .thenBy { it.first.firstAt }
            )
            .mapIndexed { i, (a, d, b) -> RankedAlert(i + 1, a, d, b) }
    }
}
