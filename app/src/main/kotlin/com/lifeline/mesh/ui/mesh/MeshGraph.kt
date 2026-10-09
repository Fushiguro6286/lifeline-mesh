package com.lifeline.mesh.ui.mesh

import com.lifeline.mesh.data.TopologyEntity
import com.lifeline.mesh.mesh.LinkState
import com.lifeline.mesh.mesh.Neighbor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the Live Mesh screen draws (DESIGN.md 8.3), built from this phone's direct links plus the Hellos it heard.
 * Pure Kotlin (no Android or Compose types) so graph building, layout and path finding are unit-tested.
 */
enum class Ring { ME, DIRECT, OUTER }

/** CONNECTED / CONNECTING / LOST are this phone's own links; HEARD = a link another phone reported in its Hello. */
enum class EdgeStyle { CONNECTED, CONNECTING, LOST, HEARD }

data class GraphNode(
    /** "o:<origin>" when the origin is known, else "e:<endpointId>". */
    val key: String,
    val origin: Int?,
    val name: String,
    val ring: Ring,
    /** Hello role code (1 needs help, 2 volunteer, 3 rescuer), null if not heard yet. */
    val role: Int?,
    /** 0–100, null if unknown. */
    val battery: Int?,
    /** Links away from this phone. */
    val hops: Int,
    /** Wall-clock ms, null if never heard. */
    val lastHeard: Long?,
    val linkState: LinkState? = null,
)

data class GraphEdge(val a: String, val b: String, val style: EdgeStyle)

data class MeshGraph(val nodes: List<GraphNode>, val edges: List<GraphEdge>) {
    val me: GraphNode get() = nodes.first { it.ring == Ring.ME }
    fun node(key: String) = nodes.firstOrNull { it.key == key }
    val connectedCount get() = edges.count { it.style == EdgeStyle.CONNECTED && (it.a == me.key || it.b == me.key) }

    fun neighborsOf(key: String): List<String> = edges.filter { it.style != EdgeStyle.LOST }
        .mapNotNull { e -> when (key) { e.a -> e.b; e.b -> e.a; else -> null } }.distinct()

    /**
     * Shortest path from [from] to [to] (node keys, both ends included) over links that are not lost,
     * or null if there is none.
     */
    fun path(from: String, to: String): List<String>? {
        if (from == to) return listOf(from)
        val prev = HashMap<String, String>()
        val queue = ArrayDeque(listOf(from))
        val seen = hashSetOf(from)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (n in neighborsOf(cur)) {
                if (!seen.add(n)) continue
                prev[n] = cur
                if (n == to) {
                    val out = ArrayList<String>()
                    var k: String? = to
                    while (k != null) { out.add(k); k = prev[k] }
                    return out.reversed()
                }
                queue.addLast(n)
            }
        }
        return null
    }

    companion object {
        const val LOST_VISIBLE_MS = 10_000L

        fun key(origin: Int) = "o:$origin"

        fun build(
            meOrigin: Int,
            meName: String,
            neighbors: List<Neighbor>,
            neighborOrigins: Map<String, Int>,
            topology: List<TopologyEntity>,
            now: Long,
        ): MeshGraph {
            val heard = topology.associateBy { it.origin }
            val me = GraphNode(key(meOrigin), meOrigin, meName, Ring.ME, null, null, 0, null)
            val nodes = LinkedHashMap<String, GraphNode>()
            nodes[me.key] = me
            val edges = LinkedHashMap<Set<String>, GraphEdge>()

            // Direct links. A phone that reconnected may have two endpoint IDs: keep its best state.
            val rank = mapOf(LinkState.CONNECTED to 0, LinkState.CONNECTING to 1, LinkState.DISCOVERED to 2, LinkState.LOST to 3)
            for (n in neighbors.sortedBy { rank.getValue(it.state) }) {
                if (n.state == LinkState.LOST && now - n.lastHeard > LOST_VISIBLE_MS) continue
                val origin = neighborOrigins[n.endpointId]
                if (origin == meOrigin) continue
                val k = origin?.let(::key) ?: "e:${n.endpointId}"
                if (k in nodes) continue
                val h = origin?.let { heard[it] }
                nodes[k] = GraphNode(
                    key = k, origin = origin, name = h?.name?.takeIf { it.isNotBlank() } ?: n.name.substringBeforeLast('-'),
                    ring = Ring.DIRECT, role = h?.role, battery = h?.battery?.takeIf { it in 0..100 }, hops = 1,
                    lastHeard = maxOf(n.lastHeard, h?.lastHeard ?: 0L), linkState = n.state,
                )
                val style = when (n.state) {
                    LinkState.CONNECTED -> EdgeStyle.CONNECTED
                    LinkState.CONNECTING, LinkState.DISCOVERED -> EdgeStyle.CONNECTING
                    LinkState.LOST -> EdgeStyle.LOST
                }
                edges[setOf(me.key, k)] = GraphEdge(me.key, k, style)
            }

            // Phones known only from Hellos.
            for (t in topology.sortedBy { it.hops }) {
                val k = key(t.origin)
                if (k in nodes || t.origin == meOrigin) continue
                nodes[k] = GraphNode(
                    key = k, origin = t.origin, name = t.name.ifBlank { "%08x".format(t.origin).takeLast(4) },
                    ring = Ring.OUTER, role = t.role, battery = t.battery.takeIf { it in 0..100 },
                    hops = maxOf(t.hops, 2), lastHeard = t.lastHeard,
                )
            }

            // Links reported in Hellos. Never add or override this phone's own links from hearsay.
            for (t in topology) {
                val a = key(t.origin)
                if (a !in nodes) continue
                for (o in t.neighbors.split(',').mapNotNull { it.trim().toIntOrNull() }) {
                    val b = key(o)
                    if (b !in nodes || a == b || a == me.key || b == me.key) continue
                    edges.getOrPut(setOf(a, b)) { GraphEdge(a, b, EdgeStyle.HEARD) }
                }
            }
            return MeshGraph(nodes.values.toList(), edges.values.toList())
        }
    }
}

/** Node centers in pixels. */
data class Point(val x: Float, val y: Float)

/**
 * Positions for [graph] in a [width] × [height] canvas (DESIGN.md 8.3): me in the middle, direct neighbors evenly
 * on an inner orbit (about 30% of the shorter side), phones further away outward next to the neighbor that
 * connects them. A long chain (me — B — C — D) curls around me (+35° per level) so it uses the diagonal space,
 * and ring spacing shrinks to fit, but never below [minRing] between me and my neighbors.
 * Computed once per topology change, never per frame.
 */
object MeshLayout {
    private val CURL = 35.0 * PI / 180
    private val SPREAD = 26.0 * PI / 180

    fun compute(graph: MeshGraph, width: Float, height: Float, margin: Float, minRing: Float = 0f): Map<String, Point> {
        val cx = width / 2f
        val cy = height / 2f
        val short = minOf(width, height)
        val angle = LinkedHashMap<String, Double>()

        // 1. Angles. Direct neighbors evenly from the top; others around the neighbor that connects them.
        val direct = graph.nodes.filter { it.ring == Ring.DIRECT }
        direct.forEachIndexed { i, n -> angle[n.key] = -PI / 2 + i * 2 * PI / direct.size }
        val outer = graph.nodes.filter { it.ring == Ring.OUTER }.sortedBy { it.hops }
        val used = HashMap<Pair<Int, Long>, Int>()
        var free = 0
        for (n in outer) {
            val anchors = graph.neighborsOf(n.key).mapNotNull { angle[it] }
            val base = if (anchors.isNotEmpty()) circularMean(anchors) else PI / 2 + (free++) * PI / 5
            val bucket = Pair(n.hops, Math.round(base * 100))
            val k = used.merge(bucket, 1, Int::plus)!! - 1
            // The first phone behind a neighbor curls 35° onward; its siblings fan out ±26° steps around that.
            val spread = if (k == 0) 0.0 else ((k + 1) / 2) * (if (k % 2 == 1) 1 else -1) * SPREAD
            angle[n.key] = base + CURL + spread
        }

        // 2. Radii. Ring spacing shrinks when the deepest chain would not fit in its direction.
        val levels = (graph.nodes.maxOfOrNull { it.hops } ?: 1).coerceAtLeast(1)
        val tightest = angle.values.minOfOrNull { reach(it, cx, cy, width, height, margin) } ?: short
        val r1 = maxOf(minRing, minOf(0.30f * short, tightest / levels))
        val out = HashMap<String, Point>()
        out[graph.me.key] = Point(cx, cy)
        for (n in graph.nodes) {
            val a = angle[n.key] ?: continue
            val avail = reach(a, cx, cy, width, height, margin)
            val step = if (levels > 1) minOf(0.20f * short, (avail - r1) / (levels - 1)) else 0f
            val r = r1 + step * (n.hops - 1)
            out[n.key] = Point(
                (cx + r * cos(a)).toFloat().coerceIn(margin, width - margin),
                (cy + r * sin(a)).toFloat().coerceIn(margin, height - margin),
            )
        }
        return out
    }

    /** Distance from the center to the margin box along angle [a]. */
    private fun reach(a: Double, cx: Float, cy: Float, w: Float, h: Float, m: Float): Float {
        val dx = cos(a); val dy = sin(a)
        val tx = if (kotlin.math.abs(dx) < 1e-6) Double.MAX_VALUE else (if (dx > 0) w - m - cx else cx - m) / kotlin.math.abs(dx)
        val ty = if (kotlin.math.abs(dy) < 1e-6) Double.MAX_VALUE else (if (dy > 0) h - m - cy else cy - m) / kotlin.math.abs(dy)
        return minOf(tx, ty).toFloat()
    }

    private fun circularMean(a: List<Double>): Double = atan2(a.sumOf { sin(it) }, a.sumOf { cos(it) })
}
