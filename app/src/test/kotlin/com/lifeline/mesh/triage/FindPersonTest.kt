package com.lifeline.mesh.triage

import com.lifeline.mesh.packet.Need
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindPersonTest {
    private val ago = { t: Long -> "${(100_000L - t) / 1000} s ago" }

    private val meena = HeardPerson(3, setOf("Meena"), alert = PersonAlert(90_000, setOf(Need.TRAPPED, Need.MEDICAL), 4, 3, 2, listOf("Kiran (Ambulance)")))
    private val ravi = HeardPerson(2, setOf("Ravi"), safeAt = 95_000, safeHops = 1, alert = PersonAlert(50_000, setOf(Need.WATER_FOOD), 2, 1, 1, emptyList()))
    private val asha = HeardPerson(5, setOf("Asha Rao"), lastHeardAt = 80_000, lastHeardHops = 3)
    private val people = listOf(meena, ravi, asha)

    @Test fun searchMatchesAnyCaseAndEveryWord() {
        assertEquals(listOf("Meena"), FindPerson.search("meen", people).map { it.name })
        assertEquals(listOf("Asha Rao"), FindPerson.search("rao asha", people).map { it.name })
        assertTrue(FindPerson.search("a", people).isEmpty())          // too short
        assertTrue(FindPerson.search("Kiran", people).isEmpty())      // a helper's name is not a person heard here
    }

    @Test fun theNewestOfAlertAndCheckInDecides() {
        assertEquals(FindPerson.Status.NEEDS_HELP, FindPerson.status(meena))
        assertEquals(FindPerson.Status.SAFE, FindPerson.status(ravi))       // checked in after the alert
        assertEquals(FindPerson.Status.HEARD, FindPerson.status(asha))
        // An alert after the check-in means they need help again.
        assertEquals(FindPerson.Status.NEEDS_HELP, FindPerson.status(ravi.copy(alert = ravi.alert!!.copy(at = 99_000))))
    }

    @Test fun plainSentences() {
        assertEquals("Meena asked for help 10 s ago: trapped, medical, 4 people, 2 hops away. Kiran (Ambulance) said help is coming.",
            FindPerson.summary(meena, ago))
        assertEquals("Ravi checked in safe 5 s ago, 1 hop away.", FindPerson.summary(ravi, ago))
        assertEquals("Asha Rao's phone was last heard 20 s ago, 3 hops away. No alert and no check-in from them.", FindPerson.summary(asha, ago))
        assertEquals("Meena asked for help 10 s ago: trapped, medical, 4 people, 2 hops away. No team has answered yet.",
            FindPerson.summary(meena.copy(alert = meena.alert!!.copy(helpers = emptyList())), ago))
    }
}
