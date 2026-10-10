package com.lifeline.mesh.ui.send

import com.lifeline.mesh.triage.Shelter
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelterNameTest {
    private fun s(note: String) = Shelter(12.97, 77.59, open = true, water = false, note = note, reportedAt = 0, reports = 1)

    @Test fun shortNamesForTheMap() {
        assertEquals("School hall", shelterName(s("School hall open, 200 beds")))
        assertEquals("Temple hall", shelterName(s("Temple hall open, 80 places")))
        assertEquals("Govt school", shelterName(s("Govt school is open for families")))
        assertEquals("Community centre", shelterName(s("Community centre")))
        assertEquals("the shelter", shelterName(s("")))
    }
}
