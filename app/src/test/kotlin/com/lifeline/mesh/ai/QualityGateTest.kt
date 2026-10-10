package com.lifeline.mesh.ai

import com.lifeline.mesh.packet.Need
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityGateTest {
    private fun draft(needs: Set<Need>, people: Int) = AlertDraft(needs, people, 2, "")

    @Test fun blocksWithoutNeedsOrPeople() {
        assertEquals(listOf(QualityGate.Missing.NEEDS, QualityGate.Missing.PEOPLE), QualityGate.check(draft(emptySet(), 0)).missing)
        assertEquals(QualityGate.Missing.PEOPLE, QualityGate.check(draft(setOf(Need.MEDICAL), 0)).ask)
        assertEquals(QualityGate.Missing.NEEDS, QualityGate.check(draft(emptySet(), 3)).ask)
        assertTrue(QualityGate.check(draft(setOf(Need.MEDICAL), 1)).ok)
    }

    @Test fun panicBypassesTheGate() {
        val p = QualityGate.panicDraft()
        assertTrue(QualityGate.check(p).ok)
        assertEquals(setOf(Need.PANIC), p.needs)
        assertEquals(3, p.severity)
        assertEquals(1, p.people)
    }

    @Test fun panicAloneIsNotANeedForNormalAlerts() {
        // A normal draft must name a real need; the panic bit only comes from the hold button.
        assertTrue(QualityGate.check(AlertDraft(setOf(Need.PANIC), 0, 3, "")).ok) // the panic draft path
        assertFalse(QualityGate.check(draft(emptySet(), 2)).ok)
    }

    private val good = GeoFix(12.97, 77.59, 12f, 1_000)
    private val poor = GeoFix(12.97, 77.59, 120f, 1_000)
    private val last = GeoFix(12.96, 77.58, 30f, 500)

    @Test fun locationRules() {
        assertEquals(LocationChoice(good, approximate = false), QualityGate.chooseLocation(good, last))
        // No fix within 8 s: last known, approximate.
        assertEquals(LocationChoice(last, approximate = true), QualityGate.chooseLocation(null, last))
        // Fresh but worse than 50 m: the better of the two, approximate.
        assertEquals(LocationChoice(last, approximate = true), QualityGate.chooseLocation(poor, last))
        assertEquals(LocationChoice(poor, approximate = true), QualityGate.chooseLocation(poor, null))
        val none = QualityGate.chooseLocation(null, null)
        assertNull(none.fix)
        assertTrue(none.approximate)
    }

    @Test fun aRecentLiveFixWinsWhenMoreAccurate() {
        val live = GeoFix(12.9701, 77.5901, 6f, 10_000)
        // Live fix 5 s old and better than the one-shot fix: used, exact.
        assertEquals(LocationChoice(live, approximate = false), QualityGate.chooseLocation(good, last, live, nowMs = 15_000))
        // Too old (over 30 s): the one-shot fix as before.
        assertEquals(LocationChoice(good, approximate = false), QualityGate.chooseLocation(good, last, live, nowMs = 45_000))
        // No fresh fix within 8 s, but a good recent live one: exact, not approximate.
        assertEquals(LocationChoice(live, approximate = false), QualityGate.chooseLocation(null, last, live, nowMs = 15_000))
        // A worse live fix does not replace a better one-shot fix.
        assertEquals(LocationChoice(good, approximate = false), QualityGate.chooseLocation(good, last, live.copy(accuracyM = 40f), nowMs = 15_000))
    }

    @Test fun exactlyFiftyMetresIsGoodEnough() {
        assertFalse(QualityGate.chooseLocation(GeoFix(0.0, 0.0, 50f, 0), null).approximate)
    }
}
