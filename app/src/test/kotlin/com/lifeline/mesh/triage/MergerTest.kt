package com.lifeline.mesh.triage

import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.Need.MEDICAL
import com.lifeline.mesh.packet.Need.TRAPPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergerTest {
    private val lat = 12.9716
    private val lon = 77.5946
    private val min = 60_000L
    /** ~1 m of latitude in degrees. */
    private val m = 1.0 / 111_320

    private fun r(
        id: Long, origin: Int = id.toInt(), dLatM: Double? = 0.0, needs: Set<Need> = setOf(TRAPPED, MEDICAL),
        at: Long = 0, severity: Int = 3, people: Int = 4, note: String = "", acked: Boolean = false, eta: Int? = null,
    ) = AlertReport(
        id, origin, dLatM?.let { lat + it * m }, dLatM?.let { lon }, 10, needs, severity, people, note,
        receivedAt = at, hops = 2, acked = acked, ackEta = eta,
    )

    @Test fun sameNeedsNearbyAndRecentMerge() {
        val g = Merger.merge(listOf(r(1), r(2, dLatM = 40.0, at = 5 * min)))
        assertEquals(1, g.size)
        assertEquals(2, g[0].count)
    }

    @Test fun differentNeedsDoNotMerge() {
        assertEquals(2, Merger.merge(listOf(r(1), r(2, needs = setOf(TRAPPED)))).size)
    }

    @Test fun farApartDoNotMerge() {
        assertEquals(2, Merger.merge(listOf(r(1), r(2, dLatM = 61.0))).size)
        assertEquals(1, Merger.merge(listOf(r(1), r(2, dLatM = 59.0))).size)
    }

    @Test fun tooLongApartDoNotMerge() {
        assertEquals(2, Merger.merge(listOf(r(1), r(2, at = 15 * min + 1))).size)
        assertEquals(1, Merger.merge(listOf(r(1), r(2, at = 15 * min))).size)
    }

    @Test fun chainsJoinTheGroup() {
        // 3 is 100 m from 1 but 50 m from 2, which is in 1's group.
        val g = Merger.merge(listOf(r(1), r(2, dLatM = 50.0, at = min), r(3, dLatM = 100.0, at = 2 * min)))
        assertEquals(1, g.size)
        assertEquals(3, g[0].count)
    }

    @Test fun unknownPositionsMergeOnlyFromTheSamePhone() {
        assertEquals(2, Merger.merge(listOf(r(1, origin = 7, dLatM = null), r(2, origin = 8, dLatM = null))).size)
        assertEquals(1, Merger.merge(listOf(r(1, origin = 7, dLatM = null), r(2, origin = 7, dLatM = null, at = min))).size)
        // A sender who resent with a fix: same phone, one has no position.
        assertEquals(1, Merger.merge(listOf(r(1, origin = 7, dLatM = null), r(2, origin = 7, at = min))).size)
    }

    @Test fun groupSummary() {
        val g = Merger.merge(
            listOf(
                r(1, at = 0, severity = 2, people = 3, note = "first"),
                r(2, at = 2 * min, severity = 3, people = 5, note = ""),
                r(3, at = 4 * min, severity = 1, people = 2, note = "latest note", acked = true, eta = 20),
            )
        ).single()
        assertEquals(3, g.severity)
        assertEquals(5, g.people)
        assertEquals(1L, g.id) // first report's ID is the stable key
        assertEquals("latest note", g.note)
        assertTrue(g.acked)
        assertEquals(20, g.ackEta)
        assertEquals(0L, g.firstAt)
        assertEquals(4 * min, g.lastAt)
    }

    @Test fun orderOfInputDoesNotMatter() {
        val a = listOf(r(1), r(2, dLatM = 40.0, at = min), r(3, needs = setOf(TRAPPED)))
        assertEquals(Merger.merge(a).map { g -> g.reports.map { it.id }.toSet() }.toSet(),
            Merger.merge(a.reversed()).map { g -> g.reports.map { it.id }.toSet() }.toSet())
    }

    @Test fun geoMath() {
        val d = GeoMath.distanceM(lat, lon, lat + 1000 * m, lon)
        assertTrue("distance $d", kotlin.math.abs(d - 1000) < 2)
        assertEquals(0.0, GeoMath.bearingDeg(lat, lon, lat + 0.01, lon), 0.5)
        assertEquals(90.0, GeoMath.bearingDeg(lat, lon, lat, lon + 0.01), 0.5)
        assertEquals("NE", GeoMath.compass(45.0))
        assertEquals("N", GeoMath.compass(359.0))
        assertFalse(Merger.sameEmergency(r(1), r(2, needs = setOf(Need.FIRE))))
    }
}
