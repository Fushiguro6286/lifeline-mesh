package com.lifeline.mesh.triage

import com.lifeline.mesh.packet.HelloRole
import kotlin.math.ceil

/** A "Help is coming" reply (Ack) seen on this phone: [responder] promised [etaMinutes] for [alertId] at [atMs]. */
data class AckSeen(val responder: Int, val alertId: Long, val etaMinutes: Int, val atMs: Long)

/** An alert a responder may be going to: who sent it, where it is (null = no position) and how urgent (0–3). */
data class AlertSpot(val origin: Int, val lat: Double?, val lon: Double?, val severity: Int = 0)

/** One open job of a responder: the alert, where it is, how urgent, and what the responder promised there. */
data class Heading(
    val responder: Int, val alertId: Long, val lat: Double?, val lon: Double?, val promisedEta: Int, val promisedAtMs: Long,
    val severity: Int = 0,
)

/**
 * Who is on the way where (map and the sender's live ETA). Pure Kotlin; no LLM is involved in any number here.
 * A responder works through its jobs most urgent first (severity 3 before 0), and jobs of the same urgency in the order
 * it accepted them. A job is done when the responder has been within [ARRIVED_M] of it (tracked by the app as positions
 * arrive, see [arrivals]), or when the person who asked for help checks in safe after the reply.
 */
object Responders {
    /** Replies older than this no longer say where anyone is going. */
    const val FRESH_MS = 2 * 60 * 60 * 1000L
    /** Closer than this to its job = arrived there. */
    const val ARRIVED_M = 75.0

    /**
     * Each responder's open jobs in the order it will do them: most urgent first, then the one accepted first.
     * [checkIns] = (origin, time) of "I'm safe" check-ins seen here. Jobs whose alert is unknown here are skipped
     * (nothing to point at). Responders with no open job are left out.
     */
    fun queues(
        acks: List<AckSeen>, alerts: Map<Long, AlertSpot>, checkIns: List<Pair<Int, Long>>, now: Long,
        /** (responder, alert) pairs it has already reached. */
        done: Set<Pair<Int, Long>> = emptySet(),
    ): Map<Int, List<Heading>> =
        acks.filter { now - it.atMs < FRESH_MS }
            .groupBy { it.responder }
            .mapValues { (who, list) ->
                list.sortedBy { it.atMs }.distinctBy { it.alertId }.mapNotNull { a ->
                    val spot = alerts[a.alertId] ?: return@mapNotNull null
                    if ((who to a.alertId) in done || checkIns.any { (origin, at) -> origin == spot.origin && at > a.atMs }) null
                    else Heading(who, a.alertId, spot.lat, spot.lon, a.etaMinutes, a.atMs, spot.severity)
                }.sortedWith(compareByDescending<Heading> { it.severity }.thenBy { it.promisedAtMs })
            }
            .filterValues { it.isNotEmpty() }

    /** Each responder's current heading: the first job of its [queues]. */
    fun headings(
        acks: List<AckSeen>, alerts: Map<Long, AlertSpot>, checkIns: List<Pair<Int, Long>>, now: Long,
        done: Set<Pair<Int, Long>> = emptySet(),
    ): Map<Int, Heading> = queues(acks, alerts, checkIns, now, done).mapValues { it.value.first() }

    /**
     * The jobs a responder does before [alertId]. If the alert is already in its [queue], the jobs ahead of it there;
     * if not (a rescuer deciding whether to accept it), every open job at least as urgent as [severity], since a new
     * job goes after those.
     */
    fun ahead(queue: List<Heading>, alertId: Long, severity: Int): List<Heading> {
        val i = queue.indexOfFirst { it.alertId == alertId }
        return if (i >= 0) queue.take(i) else queue.filter { it.severity >= severity }
    }

    /**
     * Straight-line route for a responder at ([fromLat], [fromLon]) that first does [stops] in order, then goes to
     * ([toLat], [toLon]): total metres and minutes at [travel]'s speed. Stops without a position are skipped (no leg
     * can be measured to them), so the estimate then only covers the known legs.
     */
    fun routeEta(
        fromLat: Double, fromLon: Double, stops: List<Heading>, toLat: Double, toLon: Double, travel: Travel,
    ): Pair<Double, Int> {
        var lat = fromLat; var lon = fromLon; var d = 0.0
        for (s in stops) {
            val sLat = s.lat ?: continue; val sLon = s.lon ?: continue
            d += GeoMath.distanceM(lat, lon, sLat, sLon); lat = sLat; lon = sLon
        }
        d += GeoMath.distanceM(lat, lon, toLat, toLon)
        return d to Teams.etaMinutes(d, travel)
    }

    /** Responders now within [ARRIVED_M] of their current job, as (responder, alert) pairs to add to the done set. */
    fun arrivals(headings: Map<Int, Heading>, positions: Map<Int, Pair<Double, Double>>): Set<Pair<Int, Long>> =
        headings.values.mapNotNull { h ->
            val (lat, lon) = positions[h.responder] ?: return@mapNotNull null
            val tLat = h.lat ?: return@mapNotNull null
            val tLon = h.lon ?: return@mapNotNull null
            if (GeoMath.distanceM(lat, lon, tLat, tLon) <= ARRIVED_M) h.responder to h.alertId else null
        }.toSet()

    /** Minutes left of a promised ETA, counted down from when it was given; 0 once it has passed. */
    fun promisedLeft(etaMinutes: Int, atMs: Long, now: Long): Int =
        ceil(etaMinutes - (now - atMs) / 60_000.0).toInt().coerceAtLeast(0)

    /**
     * Live estimate for a responder at ([fromLat], [fromLon]) to reach [toLat]/[toLon]: straight-line metres and minutes at
     * the role's usual speed. With [via] (its current job is somewhere else) the route goes there first.
     */
    fun liveEta(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, role: HelloRole,
        via: Pair<Double, Double>? = null,
    ): Pair<Double, Int> {
        val d = if (via == null) GeoMath.distanceM(fromLat, fromLon, toLat, toLon)
        else GeoMath.distanceM(fromLat, fromLon, via.first, via.second) + GeoMath.distanceM(via.first, via.second, toLat, toLon)
        return d to Teams.etaMinutes(d, Teams.defaultTravel(role))
    }
}
