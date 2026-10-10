package com.lifeline.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {
    private fun profile(password: String, name: String = "Asha"): Profile {
        val (salt, hash) = Passwords.protect(password)
        return Profile(id = "p1", name = name, role = Role.AMBULANCE.name, salt = salt, hash = hash)
    }

    @Test fun rightPasswordMatches() = assertTrue(Passwords.matches("river42", profile("river42")))

    @Test fun wrongPasswordFails() {
        val p = profile("river42")
        assertFalse(Passwords.matches("river43", p))
        assertFalse(Passwords.matches("", p))
        assertFalse(Passwords.matches("RIVER42", p))
    }

    @Test fun hindiAndKannadaPasswordsWork() {
        assertTrue(Passwords.matches("नदी४२", profile("नदी४२")))
        assertTrue(Passwords.matches("ನದಿ೪೨", profile("ನದಿ೪೨")))
        assertFalse(Passwords.matches("नदी४३", profile("नदी४२")))
    }

    @Test fun samePasswordGetsDifferentSalts() = assertNotEquals(profile("same").hash, profile("same").hash)

    @Test fun passwordIsNotStoredInPlainText() {
        val text = Profiles.encode(listOf(profile("river42")))
        assertFalse(text.contains("river42"))
    }

    @Test fun listRoundTrips() {
        val list = listOf(profile("a1b2"), profile("c3d4", name = "Ravi").copy(id = "p2", travel = "FOOT"))
        assertEquals(list, Profiles.decode(Profiles.encode(list)))
    }

    @Test fun badStoredDataIsAnEmptyList() {
        assertEquals(emptyList<Profile>(), Profiles.decode(null))
        assertEquals(emptyList<Profile>(), Profiles.decode(""))
        assertEquals(emptyList<Profile>(), Profiles.decode("{not json"))
    }

    @Test fun corruptHashNeverMatches() = assertFalse(Passwords.matches("river42", profile("river42").copy(hash = "%%%")))

    @Test fun unknownRoleFallsBackToNeedsHelp() =
        assertEquals(Role.NEEDS_HELP, profile("x1y2").copy(role = "PILOT").info.role)
}
