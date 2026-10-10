package com.lifeline.mesh.routing

import com.lifeline.mesh.triage.GeoMath
import com.lifeline.mesh.triage.Teams
import com.lifeline.mesh.triage.Travel

/**
 * A responder's way from where it is, through the jobs it does first, to one place: total minutes and metres, whether
 * every leg went by road, how many reported blockages it goes around, and the points to draw.
 */
data class TravelPlan(
    val minutes: Int, val meters: Double, val byRoad: Boolean, val avoidedBlocks: Int, val points: List<Pair<Double, Double>>,
)

/**
 * Travel estimates for the whole app (10 Oct 2026, on request): by road on the offline map where the map has roads and
 * the responder uses them (vehicle, on foot), around reported blockages; straight line with the detour factor for boats,
 * outside the map, or before the road network has loaded. Pure Kotlin, deterministic; numbers stay estimates.
 */
object RoutePlanner {
    /** Blockages this close to where a leg starts or ends are not avoided (help has to get there). */
    const val NEAR_END_M = 100.0

    fun roadMode(travel: Travel): RoadMode? = when (travel) {
        Travel.VEHICLE -> RoadMode.VEHICLE
        Travel.FOOT -> RoadMode.FOOT
        Travel.BOAT -> null
    }

    fun plan(
        roads: RoadMap?, from: Pair<Double, Double>, stops: List<Pair<Double, Double>>, to: Pair<Double, Double>,
        travel: Travel, blocks: List<Block> = emptyList(),
    ): TravelPlan {
        val mode = roadMode(travel)
        val legs = (listOf(from) + stops + listOf(to)).zipWithNext()
        var minutes = 0; var meters = 0.0; var byRoad = true; var avoided = 0
        val points = ArrayList<Pair<Double, Double>>()
        for ((a, b) in legs) {
            // A blockage at the place itself (an alert that says the road is blocked) must not stop help reaching it.
            val legBlocks = blocks.filter { bk ->
                GeoMath.distanceM(bk.lat, bk.lon, a.first, a.second) > NEAR_END_M && GeoMath.distanceM(bk.lat, bk.lon, b.first, b.second) > NEAR_END_M
            }
            val r = if (roads != null && mode != null) roads.route(a.first, a.second, b.first, b.second, mode, legBlocks) else null
            if (r != null) {
                minutes += r.minutes; meters += r.meters; avoided += r.avoidedBlocks
                points += if (points.isEmpty()) r.points else r.points.drop(1)
            } else {
                val d = GeoMath.distanceM(a.first, a.second, b.first, b.second)
                minutes += Teams.etaMinutes(d, travel); meters += d * travel.detour; byRoad = false
                if (points.isEmpty()) points += a
                points += b
            }
        }
        return TravelPlan(minutes.coerceAtLeast(1), meters, byRoad, avoided, points)
    }
}
