package com.lifeline.mesh.ui.mesh

import com.lifeline.mesh.data.TopologyEntity
import com.lifeline.mesh.mesh.LinkState
import com.lifeline.mesh.mesh.Neighbor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class MeshGraphTest {
    private val me = 1
    private val ravi = 2
    private val meena = 3
    private val kiran = 4
    private val now = 1_000_000L

    private fun hello(origin: Int, name: String, hops: Int, vararg neighbors: Int) =
        TopologyEntity(origin, name, role = 2, battery = 70, neighbors = neighbors.joinToString(","), lastHeard = now, hops = hops)

    /** Me — Ravi — Meena — Kiran, as heard by "me". */
    private fun line(raviState: LinkState = LinkState.CONNECTED, raviChangedAt: Long = now) = MeshGraph.build(
        meOrigin = me, meName = "Asha",
        neighbors = listOf(Neighbor("epR", "Ravi-0002", raviState, raviChangedAt)),
        neighborOrigins = mapOf("epR" to ravi),
        topology = listOf(hello(ravi, "Ravi", 1, me, meena), hello(meena, "Meena", 2, ravi, kiran), hello(kiran, "Kiran", 3, meena)),
        now = now,
    )

    @Test fun ringsFollowHops() {
        val g = line()
        assertEquals(Ring.ME, g.node("o:$me")!!.ring)
        assertEquals(Ring.DIRECT, g.node("o:$ravi")!!.ring)
        assertEquals(Ring.OUTER, g.node("o:$meena")!!.ring)
        assertEquals(3, g.node("o:$kiran")!!.hops)
        assertEquals(1, g.connectedCount)
    }

    @Test fun edgesFromOwnLinksAndHellos() {
        val g = line()
        val styles = g.edges.associate { setOf(it.a, it.b) to it.style }
        assertEquals(EdgeStyle.CONNECTED, styles[setOf("o:$me", "o:$ravi")])
        assertEquals(EdgeStyle.HEARD, styles[setOf("o:$ravi", "o:$meena")])
        assertEquals(EdgeStyle.HEARD, styles[setOf("o:$meena", "o:$kiran")])
        assertEquals(3, g.edges.size)
    }

    @Test fun lostLinkShowsForTenSecondsThenGoes() {
        val fresh = line(LinkState.LOST, raviChangedAt = now - 5_000)
        assertEquals(EdgeStyle.LOST, fresh.edges.first { it.a == "o:$me" }.style)
        val old = line(LinkState.LOST, raviChangedAt = now - 11_000)
        assertNull(old.node("o:$ravi")?.takeIf { it.ring == Ring.DIRECT })
        assertTrue(old.edges.none { it.a == "o:$me" || it.b == "o:$me" })
    }

    @Test fun neighborWithUnknownOriginStillShows() {
        val g = MeshGraph.build(me, "Asha", listOf(Neighbor("epX", "Ravi-0002", LinkState.CONNECTING, now)), emptyMap(), emptyList(), now)
        val n = g.node("e:epX")!!
        assertEquals("Ravi", n.name)
        assertEquals(EdgeStyle.CONNECTING, g.edges.single().style)
    }

    @Test fun hearsayNeverOverridesMyOwnLinks() {
        // Kiran's stale Hello says it is my neighbor, but I have no link to Kiran.
        val g = MeshGraph.build(me, "Asha", emptyList(), emptyMap(), listOf(hello(kiran, "Kiran", 2, me)), now)
        assertTrue(g.edges.isEmpty())
    }

    @Test fun pathFollowsTheLine() {
        val g = line()
        assertEquals(listOf("o:$kiran", "o:$meena", "o:$ravi", "o:$me"), g.path("o:$kiran", "o:$me"))
        assertNull(line(LinkState.LOST, now - 1_000).path("o:$kiran", "o:$me"))
    }

    @Test fun layoutKeepsNodesApartAndOnScreen() {
        val g = MeshGraph.build(
            me, "Asha",
            neighbors = listOf("A", "B", "C").map { Neighbor("ep$it", "$it-0000", LinkState.CONNECTED, now) },
            neighborOrigins = mapOf("epA" to 10, "epB" to 11, "epC" to 12),
            topology = listOf(hello(10, "A", 1, me, 20, 21), hello(20, "X", 2, 10), hello(21, "Y", 2, 10), hello(22, "Z", 2)),
            now = now,
        )
        val w = 1080f; val h = 1800f; val margin = 60f
        val pos = MeshLayout.compute(g, w, h, margin)
        assertEquals(g.nodes.size, pos.size)
        pos.values.forEach { p -> assertTrue(p.x in margin..w - margin && p.y in margin..h - margin) }
        val pts = pos.values.toList()
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            assertTrue("nodes $i and $j overlap", hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y) > 80f)
        }
        // Direct neighbors share one inner orbit, at most 30% of the shorter side from me.
        val c = pos.getValue("o:$me")
        val d = listOf(10, 11, 12).map { pos.getValue("o:$it").let { p -> hypot(p.x - c.x, p.y - c.y) } }
        assertTrue(d.all { abs(it - d[0]) < 1f } && d[0] <= 0.30f * w + 1f && d[0] > 0.2f * w)
        // X and Y hang off A, so they sit roughly in A's direction.
        val x = pos.getValue("o:20")
        assertTrue((x.y - c.y) < 0) // A is at the top, so X is above me too
    }

    @Test fun aLongChainFitsWithoutOverlap() {
        // Simulation: me — Ravi — Meena — Kiran in a phone-sized canvas.
        val g = line()
        val w = 1080f; val h = 1300f; val margin = 120f
        val pos = MeshLayout.compute(g, w, h, margin, minRing = 230f)
        pos.values.forEach { p -> assertTrue(p.x in margin..w - margin && p.y in margin..h - margin) }
        val keys = listOf("o:$me", "o:$ravi", "o:$meena", "o:$kiran")
        for (i in keys.indices) for (j in i + 1 until keys.size) {
            val a = pos.getValue(keys[i]); val b = pos.getValue(keys[j])
            assertTrue("${keys[i]} and ${keys[j]} too close", hypot(a.x - b.x, a.y - b.y) > 220f)
        }
    }

    @Test fun theElevenPhoneDrillHasNoOverlaps() {
        // The simulation drill: me — Ravi(10), Divya(11); Ravi — Meena(20), Joseph(21); Divya — Lakshmi(22), Kiran(23);
        // Meena — Farhan(30); Joseph — Suresh(31); Lakshmi — Priya(32); Farhan — Arjun(40).
        val g = MeshGraph.build(
            me, "Asha",
            neighbors = listOf("R", "D").map { Neighbor("ep$it", "$it-0000", LinkState.CONNECTED, now) },
            neighborOrigins = mapOf("epR" to 10, "epD" to 11),
            topology = listOf(
                hello(10, "Ravi", 1, me, 20, 21), hello(11, "Divya", 1, me, 22, 23),
                hello(20, "Meena", 2, 10, 30), hello(21, "Joseph", 2, 10, 31), hello(22, "Lakshmi", 2, 11, 32), hello(23, "Kiran", 2, 11),
                hello(30, "Farhan", 3, 20, 40), hello(31, "Suresh", 3, 21), hello(32, "Priya", 3, 22), hello(40, "Arjun", 4, 30),
            ),
            now = now,
        )
        val w = 1080f; val h = 1300f; val margin = 120f; val gap = 200f
        val pos = MeshLayout.compute(g, w, h, margin, minRing = 270f, minGap = gap)
        assertEquals(11, pos.size)
        pos.values.forEach { p -> assertTrue(p.x in margin..w - margin && p.y in margin..h - margin) }
        val keys = pos.keys.toList()
        for (i in keys.indices) for (j in i + 1 until keys.size) {
            val a = pos.getValue(keys[i]); val b = pos.getValue(keys[j])
            assertTrue("${keys[i]} and ${keys[j]} overlap", hypot(a.x - b.x, a.y - b.y) > gap * 0.9f)
        }
        assertEquals(Point(w / 2, h / 2), pos.getValue("o:$me")) // I stay in the middle
    }
}
