package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.Packet

/** How hard this phone works for the mesh (10 Oct 2026, low-battery survival mode). */
enum class PowerMode { NORMAL, SAVER, CRITICAL }

/**
 * Low-battery survival mode: in a flood, relay phones die before help arrives. Below [SAVER_PCT] (not charging) a
 * phone says Hello less often and passes on only what saves lives; below [CRITICAL_PCT] even less. Its own packets
 * always go out. What it does not pass on stays in its store-and-forward memory and goes out when it is charging
 * again (or to a phone that connects and asks for it). Pure Kotlin.
 */
object PowerPolicy {
    const val SAVER_PCT = 20
    const val CRITICAL_PCT = 10

    /** [pct] 0–100 (anything else = unknown, treated as fine); [enabled] = the Settings switch. */
    fun mode(pct: Int, charging: Boolean, enabled: Boolean): PowerMode = when {
        !enabled || charging || pct !in 0..100 -> PowerMode.NORMAL
        pct <= CRITICAL_PCT -> PowerMode.CRITICAL
        pct <= SAVER_PCT -> PowerMode.SAVER
        else -> PowerMode.NORMAL
    }

    /** Read from a neighbour's Hello: its battery says which mode it is in (unknown = 255 = normal). */
    fun modeOf(battery: Int?): PowerMode = mode(battery ?: 255, charging = false, enabled = true)

    /**
     * Between Hellos. Neighbours forget a phone after 60 s of silence (Topology), so even a critical phone says Hello
     * twice a minute.
     */
    fun helloIntervalMs(m: PowerMode): Long = when (m) {
        PowerMode.NORMAL -> 10_000L
        PowerMode.SAVER -> 20_000L
        PowerMode.CRITICAL -> 30_000L
    }

    /**
     * Whether this phone passes on someone else's packet.
     * - Saver: everything that helps someone now: alerts, replies, "I'm safe", warnings and positions. Community
     *   reports wait; measurement pings are dropped.
     * - Critical: only urgent alerts (severity 3), replies, serious warnings, "I'm safe" and rescue teams' positions
     *   (people waiting watch them come).
     */
    fun relays(m: PowerMode, p: Packet): Boolean = when (m) {
        PowerMode.NORMAL -> true
        PowerMode.SAVER -> p !is Packet.Report && p !is Packet.Ping
        PowerMode.CRITICAL -> when (p) {
            is Packet.Alert -> p.severity >= 3
            is Packet.Ack, is Packet.CheckIn -> true
            is Packet.Warning -> p.level >= 2
            is Packet.Position -> p.role.isTeam
            else -> false
        }
    }
}
