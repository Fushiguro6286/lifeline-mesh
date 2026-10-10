package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.triage.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayGapsTest {
    private val lat0 = 12.9716
    private val lon0 = 77.5946
    private val m = 180.0 / (Math.PI * 6_371_000.0)
    private val now = 10_000_000L

    private fun phone(origin: Int, name: String, northM: Double, heardAgoMs: Long = 0, role: HelloRole = HelloRole.NEEDS_HELP) =
        GapPhone(origin, name, role, lat0 + northM * m, lon0, now - heardAgoMs)

    private val me = phone(1, "Me", 0.0, role = HelloRole.AMBULANCE)

    private fun find(phones: List<GapPhone>, links: List<Pair<Int, Int>> = emptyList(), range: Double? = 500.0, alerts: Set<Int> = emptySet(),
                     roles: Map<Int, HelloRole> = emptyMap(), batteries: Map<Int, Int> = emptyMap()) =
        RelayGaps.find(me, phones, alerts, links, phones.associate { it.origin to it.name }, roles, batteries, range, now)

    @Test fun aLostPhoneIsBridgedFromTheNearestPhoneStillHeard() {
        val ravi = phone(2, "Ravi", 400.0)                                 // heard just now
        val joseph = phone(3, "Joseph", 1_500.0, heardAgoMs = 4 * 60_000) // quiet for 4 minutes
        val g = find(listOf(ravi, joseph), range = 500.0)
        val b = g.bridges.single()
        assertEquals("Joseph", b.phone.name)
        assertEquals("Ravi", b.from.name)
        assertEquals(1_100.0, b.distanceM, 2.0)
        // Spacing 80 % of 500 m = 400 m: 1,100 m needs 2 people in between, a third and two thirds of the way.
        assertEquals(400.0, g.spacingM, 0.01)
        assertEquals(2, b.relays)
        val d = b.standAt.map { (la, lo) -> GeoMath.distanceM(ravi.lat!!, ravi.lon!!, la, lo) }
        assertEquals(366.7, d[0], 2.0); assertEquals(733.3, d[1], 2.0)
    }

    @Test fun whoCountsAsLost() {
        val fresh = phone(2, "Fresh", 900.0, heardAgoMs = 60_000)            // within the 3 minutes
        val old = phone(3, "Old", 900.0, heardAgoMs = 40 * 60_000)           // forgotten after 30 minutes
        val inGraph = phone(4, "Hello", 900.0, heardAgoMs = 5 * 60_000)      // its Hellos still arrive
        val g = find(listOf(fresh, old, inGraph), links = listOf(1 to 4))
        assertTrue(g.bridges.isEmpty())
    }

    @Test fun closeButSilentNeedsNoRelayAndFarIsTooFar() {
        val near = phone(2, "Near", 150.0, heardAgoMs = 5 * 60_000)
        val far = phone(3, "Far", 9_000.0, heardAgoMs = 5 * 60_000)
        val g = find(listOf(near, far), range = 200.0, alerts = setOf(3))
        // An open alert comes first.
        assertEquals(listOf("Far", "Near"), g.bridges.map { it.phone.name })
        assertTrue(g.bridges[0].tooFar); assertTrue(g.bridges[0].standAt.isEmpty())
        assertEquals(0, g.bridges[1].relays)  // within range: switched off, flat or behind walls
    }

    @Test fun withoutAMeasuredLinkTheRangeIsAssumedAndSaysSo() {
        val g = find(listOf(phone(2, "Lost", 300.0, heardAgoMs = 4 * 60_000)), range = null)
        assertEquals(RelayGaps.ASSUMED_RANGE_M, g.rangeM, 0.0)
        assertTrue(!g.rangeMeasured)
        assertEquals(6, g.bridges.single().relays) // 300 m at 48 m spacing
    }

    @Test fun aPhoneThatIsMyOnlyLinkToOthersIsAWeakPoint() {
        // The simulated layout: Me - Ravi - Meena - Farhan - Arjun (fire), Ravi - Joseph - Suresh (police);
        // Me - Divya - Kiran (ambulance).
        val links = listOf(1 to 2, 2 to 3, 3 to 5, 5 to 8, 2 to 4, 4 to 9, 1 to 6, 6 to 7)
        val names = mapOf(2 to "Ravi", 3 to "Meena", 4 to "Joseph", 5 to "Farhan", 6 to "Divya", 7 to "Kiran", 8 to "Arjun", 9 to "Suresh")
        val roles = mapOf(7 to HelloRole.AMBULANCE, 8 to HelloRole.FIREFIGHTER, 9 to HelloRole.POLICE)
        val weak = RelayGaps.weakPoints(me, links, names, roles, mapOf(2 to 8, 3 to 18))
        // Ravi at 8 % (critical) first: he alone links me to five phones, two of them rescue teams.
        val ravi = weak.first()
        assertEquals("Ravi", ravi.phone.name)
        assertEquals(PowerMode.CRITICAL, ravi.mode)
        assertEquals(listOf("Arjun", "Farhan", "Joseph", "Meena", "Suresh"), ravi.cutOff)
        assertEquals(2, ravi.teams)
        assertTrue(weak.any { it.phone.name == "Divya" && it.cutOff == listOf("Kiran") && it.teams == 1 })
        assertTrue(weak.any { it.phone.name == "Meena" && it.cutOff == listOf("Arjun", "Farhan") })
        // An end of a line links nobody.
        assertTrue(weak.none { it.phone.name in setOf("Kiran", "Arjun", "Suresh") })
    }

    @Test fun aRingHasNoWeakPoint() {
        assertTrue(RelayGaps.weakPoints(me, listOf(1 to 2, 2 to 3, 3 to 1), emptyMap(), emptyMap(), emptyMap()).isEmpty())
    }

    @Test fun articulationPointsOnSmallGraphs() {
        fun adj(vararg e: Pair<Int, Int>): Map<Int, Set<Int>> {
            val a = HashMap<Int, MutableSet<Int>>()
            e.forEach { (x, y) -> a.getOrPut(x) { HashSet() } += y; a.getOrPut(y) { HashSet() } += x }
            return a
        }
        assertEquals(setOf(2), RelayGaps.articulation(adj(1 to 2, 2 to 3)))
        assertEquals(emptySet<Int>(), RelayGaps.articulation(adj(1 to 2, 2 to 3, 3 to 1)))
        assertEquals(setOf(-5, 3), RelayGaps.articulation(adj(1 to -5, -5 to 3, 3 to 4, 3 to 6, 4 to 6))) // negative origins too
    }
}
