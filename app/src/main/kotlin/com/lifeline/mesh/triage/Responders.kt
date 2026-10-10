package com.lifeline.mesh.triage

import com.lifeline.mesh.packet.HelloRole
import kotlin.math.ceil

/** A "Help is coming" reply (Ack) seen on this phone: [responder] promised [etaMinutes] for [alertId] at [atMs]. */
data class AckSeen(val responder: Int, val alertId: Long, val etaMinutes: Int, val atMs: Long)

/** An alert a responder may be going to: who sent it and where it is (null = no position). */
data class AlertSpot(val origin: Int, val lat: Double?, val lon: Double?)

/** Where a responder is going now: the alert of its oldest open job, and what it promised there. */
data class Heading(val responder: Int, val alertId: Long, val lat: Double?, val lon: Double?, val promisedEta: Int, val promisedAtMs: Long)

/**
 * Who is on the way where (map and the sender's live ETA). Pure Kotlin; no LLM is involved in any number here.
 * A responder works through its jobs in the order it accepted them; a job is done when the responder has been within
 * [ARRIVED_M] of it (tracked by the app as positions arrive, see [arrivals]), or when the person who asked for help
 * checks in safe after the reply.
 */
object Responders {
    /** Replies older than this no longer say where anyone is going. */
    const val FRESH_MS = 2 * 60 * 60 * 1000L
    /** Closer than this to its job = arrived there. */
    const val ARRIVED_M = 75.0

    /**
     * Each responder's current heading. [checkIns] = (origin, time) of "I'm safe" check-ins seen here.
     * Jobs whose alert is unknown here are skipped (nothing to point at).
     */
    fun headings(
        acks: List<AckSeen>, alerts: Map<Long, AlertSpot>, checkIns: List<Pair<Int, Long>>, now: Long,
        /** (responder, alert) pairs it has already reached. */
        done: Set<Pair<Int, Long>> = emptySet(),
    ): Map<Int, Heading> =
        acks.filter { now - it.atMs < FRESH_MS }
            .groupBy { it.responder }
            .mapNotNull { (who, list) ->
                val open = list.sortedBy { it.atMs }.distinctBy { it.alertId }.firstOrNull { a ->
                    val spot = alerts[a.alertId] ?: return@firstOrNull false
                    (who to a.alertId) !in done && checkIns.none { (origin, at) -> origin == spot.origin && at > a.atMs }
                } ?: return@mapNotNull null
                val spot = alerts.getValue(open.alertId)
                who to Heading(who, open.alertId, spot.lat, spot.lon, open.etaMinutes, open.atMs)
            }.toMap()

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
