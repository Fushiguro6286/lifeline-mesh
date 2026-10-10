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
    /** Came through the sync server (an online phone with an open alert), not the mesh: [hops] means nothing then. */
    val viaInternet: Boolean = false,
)

/**
 * When to send my Position (Phase 10): at once for the first fix, then every [MOVING_MS] while I have moved at least
 * [MOVE_M], and every [HEARTBEAT_MS] anyway so a still phone does not vanish from others' maps. Also (10 Oct 2026)
 * after [IMPROVED_MS] when the fix became at least [IMPROVED_M] more accurate, so others get the better position
 * soon. Pure; [due] is checked every few seconds by the service.
 */
object PositionBeacon {
    const val MOVING_MS = 30_000L
    const val HEARTBEAT_MS = 120_000L
    const val MOVE_M = 25.0
    const val IMPROVED_MS = 15_000L
    /** One step of the packet's 10 m accuracy field. */
    const val IMPROVED_M = 10f
    /** Positions older than this are no longer shown. */
    const val SHOW_MS = 10 * 60_000L

    data class Sent(val lat: Double, val lon: Double, val atMs: Long, val accuracyM: Float? = null)

    fun due(last: Sent?, lat: Double, lon: Double, now: Long, accuracyM: Float? = null): Boolean {
        if (last == null) return true
        val age = now - last.atMs
        if (age >= HEARTBEAT_MS) return true
        if (age >= IMPROVED_MS && accuracyM != null && (last.accuracyM == null || accuracyM <= last.accuracyM - IMPROVED_M)) return true
        return age >= MOVING_MS && GeoMath.distanceM(last.lat, last.lon, lat, lon) >= MOVE_M
    }

    /** Fresh positions I may show, by the Teams visibility rule. */
    fun visible(all: Collection<PeerPosition>, viewer: HelloRole, now: Long): List<PeerPosition> =
        all.filter { now - it.heardAt < SHOW_MS && com.lifeline.mesh.triage.Teams.canSeePosition(viewer, it.role, mine = false) }
}

/** A measured distance to a phone this phone heard directly (one link), from both GPS fixes. */
data class LinkRange(val metres: Double, val uncertaintyM: Double, val peerName: String)

/**
 * How far apart two directly linked phones were (CLAUDE.md rule 3: measured, never assumed). A Position packet that
 * travelled one link came straight from that phone, so at that moment the link worked over the GPS distance between
 * us. Only fixes that are fresh and accurate enough count; the uncertainty is both fixes' accuracy added.
 */
object LinkRanges {
    /** My fix must be at most this old when their Position arrives. */
    const val MAX_FIX_AGE_MS = 60_000L
    /** Both accuracies added must be at most this, or the distance says little. */
    const val MAX_UNCERTAINTY_M = 50.0

    fun measure(peer: PeerPosition, linkHops: Int, myLat: Double, myLon: Double, myAccuracyM: Float?, myFixAt: Long, now: Long): LinkRange? {
        if (linkHops != 1) return null
        val mine = myAccuracyM?.toDouble() ?: return null
        val theirs = peer.accuracyM?.toDouble() ?: return null
        if (now - myFixAt > MAX_FIX_AGE_MS || mine + theirs > MAX_UNCERTAINTY_M) return null
        return LinkRange(GeoMath.distanceM(myLat, myLon, peer.lat, peer.lon), mine + theirs, peer.name)
    }
}
