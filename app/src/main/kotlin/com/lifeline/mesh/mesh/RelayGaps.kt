package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.triage.GeoMath
import kotlin.math.ceil

/** A phone as the gap finder sees it. [lat]/[lon] null = no position known. [heardAt] = wall ms, last Position or Hello. */
data class GapPhone(
    val origin: Int,
    val name: String,
    val role: HelloRole,
    val lat: Double?,
    val lon: Double?,
    val heardAt: Long,
    val battery: Int? = null,
)

/**
 * A phone the mesh lost: heard recently, silent now. [from] = the nearest phone still heard; [standAt] = where
 * [relays] people with the app would link it again, evenly spaced on the straight line between them. [tooFar] = more
 * than [RelayGaps.MAX_RELAYS] would be needed.
 */
data class Bridge(
    val phone: GapPhone,
    val hasAlert: Boolean,
    val silentMs: Long,
    val from: GapPhone,
    val distanceM: Double,
    val relays: Int,
    val standAt: List<Pair<Double, Double>>,
    val tooFar: Boolean,
)

/** A phone that is this phone's only link to [cutOff] (named phones), [teams] of them rescue teams. */
data class WeakPoint(val phone: GapPhone, val cutOff: List<String>, val teams: Int, val mode: PowerMode)

data class Gaps(val bridges: List<Bridge>, val weak: List<WeakPoint>, val spacingM: Double, val rangeM: Double, val rangeMeasured: Boolean) {
    val isEmpty get() = bridges.isEmpty() && weak.isEmpty()
}

/**
 * Relay gap finder (10 Oct 2026): where the mesh is broken or about to break, and where a person with the app should
 * stand to fix it. Deterministic, pure Kotlin.
 *
 * - Lost phones: a Position heard in the last [FORGET_MS] but not in the last [silentMs] (positions go out at least
 *   every 2 minutes), and the phone is not in the live Hello graph either. The gap to the nearest phone still heard is
 *   bridged by people standing every [SPACING] × the longest link this phone has measured (LinkRanges), or
 *   [ASSUMED_RANGE_M] when none is measured yet, and says which.
 * - Weak points: in the Hello graph (about 3 hops around this phone), a phone that is this phone's only link to
 *   others (an articulation point): if it stops, they are out of reach. Its Hello says its battery, so a weak point
 *   that is also saving battery comes first, then the one cutting off the most rescue teams, then the most phones.
 */
object RelayGaps {
    const val SILENT_MS = 3 * 60_000L
    /** Virtual phones send a Position every 20 s. */
    const val SIM_SILENT_MS = 60_000L
    const val FORGET_MS = 30 * 60_000L
    /** Bluetooth between phones outdoors, before any link has been measured. */
    const val ASSUMED_RANGE_M = 60.0
    /** Stand closer than the longest link that worked: that one was the best case. */
    const val SPACING = 0.8
    const val MAX_RELAYS = 5

    fun find(
        me: GapPhone,
        phones: List<GapPhone>,
        /** Phones with an open alert: their gaps come first. */
        alertOrigins: Set<Int>,
        /** Links of the live Hello graph, mine included. */
        links: List<Pair<Int, Int>>,
        names: Map<Int, String>,
        roles: Map<Int, HelloRole>,
        batteries: Map<Int, Int>,
        measuredRangeM: Double?,
        now: Long,
        silentMs: Long = SILENT_MS,
    ): Gaps {
        val range = measuredRangeM?.takeIf { it > 0 } ?: ASSUMED_RANGE_M
        val spacing = range * SPACING
        val inGraph = links.flatMap { listOf(it.first, it.second) }.toSet()
        val placed = phones.filter { it.lat != null && it.lon != null && it.origin != me.origin }
        val live = (placed.filter { now - it.heardAt < silentMs } + listOfNotNull(me.takeIf { it.lat != null && it.lon != null }))
        val lost = placed.filter { now - it.heardAt in silentMs until FORGET_MS && it.origin !in inGraph }

        val bridges = lost.mapNotNull { p ->
            val from = live.minByOrNull { GeoMath.distanceM(it.lat!!, it.lon!!, p.lat!!, p.lon!!) } ?: return@mapNotNull null
            val d = GeoMath.distanceM(from.lat!!, from.lon!!, p.lat!!, p.lon!!)
            val relays = (ceil(d / spacing).toInt() - 1).coerceAtLeast(0)
            val tooFar = relays > MAX_RELAYS
            val (aLat, aLon, bLat, bLon) = listOf(from.lat!!, from.lon!!, p.lat!!, p.lon!!)
            val stand = if (tooFar) emptyList() else (1..relays).map { k ->
                val t = k.toDouble() / (relays + 1)
                aLat + (bLat - aLat) * t to aLon + (bLon - aLon) * t
            }
            Bridge(p.copy(battery = p.battery ?: batteries[p.origin]), p.origin in alertOrigins, now - p.heardAt, from, d, relays, stand, tooFar)
        }.sortedWith(compareByDescending<Bridge> { it.hasAlert }.thenBy { it.distanceM })

        return Gaps(bridges, weakPoints(me, links, names, roles, batteries), spacing, range, measuredRangeM != null && measuredRangeM > 0)
    }

    /** Articulation points of the Hello graph: phones whose loss puts others out of this phone's reach. */
    internal fun weakPoints(
        me: GapPhone, links: List<Pair<Int, Int>>, names: Map<Int, String>, roles: Map<Int, HelloRole>, batteries: Map<Int, Int>,
    ): List<WeakPoint> {
        val adj = HashMap<Int, MutableSet<Int>>()
        for ((a, b) in links) if (a != b) { adj.getOrPut(a) { HashSet() } += b; adj.getOrPut(b) { HashSet() } += a }
        adj.getOrPut(me.origin) { HashSet() }
        val isTeam = { o: Int -> roles[o]?.isTeam == true }

        return articulation(adj).filter { it != me.origin }.mapNotNull { x ->
            // Everything still reachable from me without x; the rest is cut off.
            val seen = hashSetOf(x, me.origin)
            val stack = ArrayDeque(listOf(me.origin))
            while (stack.isNotEmpty()) adj.getValue(stack.removeLast()).forEach { v -> if (seen.add(v)) stack.addLast(v) }
            val cut = adj.keys.filter { it !in seen }
            if (cut.isEmpty()) return@mapNotNull null
            val label = { o: Int -> names[o]?.ifBlank { null } ?: "%08x".format(o).takeLast(4) }
            WeakPoint(
                GapPhone(x, names[x].orEmpty(), roles[x] ?: HelloRole.VOLUNTEER, null, null, 0, batteries[x]),
                cut.map(label).sorted(), cut.count(isTeam), PowerPolicy.modeOf(batteries[x]),
            )
        }.sortedWith(compareByDescending<WeakPoint> { it.mode.ordinal }.thenByDescending { it.teams }.thenByDescending { it.cutOff.size })
    }

    /** Tarjan's articulation points, iterative (no recursion limits on a big mesh). */
    internal fun articulation(adj: Map<Int, Set<Int>>): Set<Int> {
        val disc = HashMap<Int, Int>(); val low = HashMap<Int, Int>(); val out = HashSet<Int>()
        var time = 0
        for (root in adj.keys) {
            if (root in disc) continue
            disc[root] = time; low[root] = time; time++
            var rootChildren = 0
            // (node, parent, iterator over neighbours); origins are any Int, so "no parent" is null
            val stack = ArrayDeque<Triple<Int, Int?, Iterator<Int>>>()
            stack.addLast(Triple(root, null, adj.getValue(root).iterator()))
            while (stack.isNotEmpty()) {
                val (u, parent, it) = stack.last()
                if (it.hasNext()) {
                    val v = it.next()
                    if (v == parent) continue
                    if (v in disc) { low[u] = minOf(low.getValue(u), disc.getValue(v)); continue }
                    disc[v] = time; low[v] = time; time++
                    if (u == root) rootChildren++
                    stack.addLast(Triple(v, u, adj.getValue(v).iterator()))
                } else {
                    stack.removeLast()
                    val p = stack.lastOrNull()?.first ?: continue
                    low[p] = minOf(low.getValue(p), low.getValue(u))
                    if (p != root && low.getValue(u) >= disc.getValue(p)) out += p
                }
            }
            if (rootChildren > 1) out += root
        }
        return out
    }
}
