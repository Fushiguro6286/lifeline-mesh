package com.lifeline.mesh.triage

/** One community report about a place to go: [kind] 2 water available, 3 shelter open, 4 shelter full (packet codes). */
data class PlaceReport(val id: Long, val kind: Int, val lat: Double, val lon: Double, val note: String, val receivedAt: Long)

/**
 * A shelter (or water point) as the reports about it say now: several reports within [Shelters.SAME_PLACE_M] are the
 * same place, and the newest one decides whether a shelter is open or full.
 */
data class Shelter(
    val lat: Double,
    val lon: Double,
    val open: Boolean,
    val water: Boolean,
    /** The newest report's note, e.g. "School hall open, 200 beds". */
    val note: String,
    val reportedAt: Long,
    val reports: Int,
)

/**
 * "Where do I go?" (10 Oct 2026): from the community reports this phone holds, which shelters are open now, and which
 * open one is quickest to walk to. Deterministic; the walking time comes from the offline road map, around reported
 * blocked roads. Pure Kotlin.
 */
object Shelters {
    const val SHELTER_OPEN = 3
    const val SHELTER_FULL = 4
    const val WATER = 2
    /** Reports this close together are about the same building. */
    const val SAME_PLACE_M = 100.0
    /** Only the nearest few by straight line are routed (walking routes take a few milliseconds each). */
    const val ROUTE_CANDIDATES = 4

    /** Shelters and water points, newest report first within each place. */
    fun places(reports: List<PlaceReport>): List<Shelter> {
        val groups = ArrayList<MutableList<PlaceReport>>()
        for (r in reports.filter { it.kind in setOf(WATER, SHELTER_OPEN, SHELTER_FULL) }.sortedByDescending { it.receivedAt }) {
            // Water points and shelters are kept apart even when close: a tap beside a full hall is still water.
            val g = groups.firstOrNull { g ->
                val head = g.first()
                (head.kind == WATER) == (r.kind == WATER) && GeoMath.distanceM(head.lat, head.lon, r.lat, r.lon) <= SAME_PLACE_M
            }
            if (g != null) g += r else groups += mutableListOf(r)
        }
        return groups.map { g ->
            val newest = g.first()
            Shelter(newest.lat, newest.lon, open = newest.kind != SHELTER_FULL, water = newest.kind == WATER,
                note = newest.note, reportedAt = newest.receivedAt, reports = g.size)
        }
    }

    /** The open shelters (not water points), nearest first in a straight line from ([lat], [lon]). */
    fun openNear(all: List<Shelter>, lat: Double, lon: Double): List<Shelter> =
        all.filter { it.open && !it.water }.sortedBy { GeoMath.distanceM(lat, lon, it.lat, it.lon) }

    fun fullCount(all: List<Shelter>): Int = all.count { !it.open && !it.water }

    /**
     * The open shelter quickest to reach on foot: the [ROUTE_CANDIDATES] nearest are timed with [minutes] (a walking
     * route on the map, or a straight-line estimate), and the quickest wins; ties go to the nearer.
     */
    fun <T> quickest(open: List<Shelter>, minutes: (Shelter) -> Pair<Int, T>): Triple<Shelter, Int, T>? =
        open.take(ROUTE_CANDIDATES).map { s -> minutes(s).let { (m, extra) -> Triple(s, m, extra) } }.minByOrNull { it.second }
}
