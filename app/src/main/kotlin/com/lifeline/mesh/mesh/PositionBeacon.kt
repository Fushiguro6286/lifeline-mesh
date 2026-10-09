package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.triage.GeoMath

/** Where another phone was when it last said so (Phase 10). Kept in memory only: a position is minutes-fresh. */
data class PeerPosition(
    val origin: Int,
    val name: String,
    val role: HelloRole,
    val lat: Double,
    val lon: Double,
    val accuracyM: Int?,
    /** Links travelled to reach this phone. */
    val hops: Int,
    /** Wall-clock ms when it arrived here. */
    val heardAt: Long,
)

/**
 * When to send my Position (Phase 10): at once for the first fix, then every [MOVING_MS] while I have moved at least
 * [MOVE_M], and every [HEARTBEAT_MS] anyway so a still phone does not vanish from others' maps. Pure; [due] is
 * checked every few seconds by the service.
 */
object PositionBeacon {
    const val MOVING_MS = 30_000L
    const val HEARTBEAT_MS = 120_000L
    const val MOVE_M = 25.0
    /** Positions older than this are no longer shown. */
    const val SHOW_MS = 10 * 60_000L

    data class Sent(val lat: Double, val lon: Double, val atMs: Long)

    fun due(last: Sent?, lat: Double, lon: Double, now: Long): Boolean {
        if (last == null) return true
        val age = now - last.atMs
        if (age >= HEARTBEAT_MS) return true
        return age >= MOVING_MS && GeoMath.distanceM(last.lat, last.lon, lat, lon) >= MOVE_M
    }

    /** Fresh positions I may show, by the Teams visibility rule. */
    fun visible(all: Collection<PeerPosition>, viewer: HelloRole, now: Long): List<PeerPosition> =
        all.filter { now - it.heardAt < SHOW_MS && com.lifeline.mesh.triage.Teams.canSeePosition(viewer, it.role, mine = false) }
}
