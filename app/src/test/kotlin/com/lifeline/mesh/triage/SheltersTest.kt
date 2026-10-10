package com.lifeline.mesh.triage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheltersTest {
    private val lat0 = 12.9716
    private val lon0 = 77.5946
    private val m = 180.0 / (Math.PI * 6_371_000.0)
    private var id = 0L

    private fun r(kind: Int, northM: Double, at: Long, note: String = "") = PlaceReport(++id, kind, lat0 + northM * m, lon0, note, at)

    @Test fun reportsAboutTheSamePlaceMergeAndTheNewestDecides() {
        val all = Shelters.places(listOf(
            r(Shelters.SHELTER_OPEN, 500.0, 1_000, "School hall open"),
            r(Shelters.SHELTER_OPEN, 530.0, 2_000, "School hall, 200 beds"),    // same hall, 30 m away
            r(Shelters.SHELTER_FULL, 510.0, 3_000, "Full"),                     // newest: now full
            r(Shelters.SHELTER_OPEN, 1_200.0, 1_500, "Temple hall open"),
        ))
        assertEquals(2, all.size)
        val school = all.single { it.note == "Full" }
        assertFalse(school.open); assertEquals(3, school.reports)
        assertTrue(all.single { it.note == "Temple hall open" }.open)
        assertEquals(1, Shelters.fullCount(all))
        // Reopened later: open again.
        val reopened = Shelters.places(listOf(r(Shelters.SHELTER_FULL, 500.0, 1_000), r(Shelters.SHELTER_OPEN, 500.0, 2_000, "Space again")))
        assertTrue(reopened.single().open)
    }

    @Test fun waterPointsAreNotShelters() {
        val all = Shelters.places(listOf(r(Shelters.WATER, 100.0, 1_000, "Tanker"), r(Shelters.SHELTER_FULL, 120.0, 2_000)))
        assertEquals(2, all.size) // kept apart even 20 m away
        assertTrue(Shelters.openNear(all, lat0, lon0).isEmpty())
        assertTrue(all.single { it.water }.open)
        // Other reports (road blocked = 1) are not places to go.
        assertTrue(Shelters.places(listOf(r(1, 100.0, 1_000))).isEmpty())
    }

    @Test fun theQuickestToReachWinsNotTheNearest() {
        val all = Shelters.places(listOf(r(Shelters.SHELTER_OPEN, 400.0, 1, "Near, across the river"), r(Shelters.SHELTER_OPEN, 900.0, 1, "Far, straight road")))
        val open = Shelters.openNear(all, lat0, lon0)
        assertEquals("Near, across the river", open.first().note)
        // The near one is a 40-minute walk round the river; the far one 12 minutes.
        val best = Shelters.quickest(open) { s -> (if (s.note.startsWith("Near")) 40 else 12) to Unit }!!
        assertEquals("Far, straight road", best.first.note)
        assertEquals(12, best.second)
        assertNull(Shelters.quickest(emptyList()) { 1 to Unit })
    }
}
