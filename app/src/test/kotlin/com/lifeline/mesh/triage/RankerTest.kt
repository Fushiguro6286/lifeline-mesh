package com.lifeline.mesh.triage

import com.lifeline.mesh.packet.Need
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankerTest {
    private val lat = 12.9716
    private val lon = 77.5946
    private val m = 1.0 / 111_320

    private fun alert(id: Long, severity: Int, reports: Int = 1, distM: Double? = 100.0, at: Long = 0): MergedAlert =
        MergedAlert(List(reports) { i ->
            AlertReport(id * 10 + i, id.toInt(), distM?.let { lat + it * m }, distM?.let { lon }, 10, setOf(Need.TRAPPED),
                severity, 2, "", at + i, 1, false, null)
        })

    private fun order(list: List<RankedAlert>) = list.map { it.alert.id / 10 }

    @Test fun severityFirst() {
        val ranked = Ranker.rank(listOf(alert(1, 1), alert(2, 3), alert(3, 2)), lat, lon)
        assertEquals(listOf(2L, 3L, 1L), order(ranked))
        assertEquals(listOf(1, 2, 3), ranked.map { it.rank })
    }

    @Test fun thenMoreReports() {
        assertEquals(listOf(2L, 1L), order(Ranker.rank(listOf(alert(1, 3, reports = 1), alert(2, 3, reports = 3)), lat, lon)))
    }

    @Test fun thenNearestAndUnknownDistanceLast() {
        val ranked = Ranker.rank(listOf(alert(1, 3, distM = 900.0), alert(2, 3, distM = null), alert(3, 3, distM = 200.0)), lat, lon)
        assertEquals(listOf(3L, 1L, 2L), order(ranked))
        assertTrue(kotlin.math.abs(ranked[0].distanceM!! - 200) < 2)
        assertNull(ranked[2].distanceM)
    }

    @Test fun rescuerWithoutPositionStillRanksBySeverityAndCount() {
        val ranked = Ranker.rank(listOf(alert(1, 2), alert(2, 3), alert(3, 3, reports = 2)), null, null)
        assertEquals(listOf(3L, 2L, 1L), order(ranked))
        assertTrue(ranked.all { it.distanceM == null && it.bearingDeg == null })
    }

    @Test fun tiesKeepAStableOrder() {
        val ranked = Ranker.rank(listOf(alert(1, 3, at = 50), alert(2, 3, at = 10)), lat, lon)
        assertEquals(listOf(2L, 1L), order(ranked)) // same severity, count and distance: oldest first
    }

    @Test fun bearingPointsTowardTheAlert() {
        val r = Ranker.rank(listOf(alert(1, 3, distM = 500.0)), lat, lon).single()
        assertEquals(0.0, r.bearingDeg!!, 1.0) // due north
    }
}
