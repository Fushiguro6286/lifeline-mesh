package com.lifeline.mesh.triage

import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.Need
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamsTest {
    @Test fun etaUsesDetourAndSpeed() {
        // 5 km by road: 5 × 1.3 = 6.5 km at 25 km/h = 15.6 min → 16.
        assertEquals(16, Teams.etaMinutes(5_000.0, Travel.VEHICLE))
        // 1 km on foot: 1.3 km at 4 km/h = 19.5 min → 20.
        assertEquals(20, Teams.etaMinutes(1_000.0, Travel.FOOT))
        // 2 km by boat, no detour: 15 min.
        assertEquals(15, Teams.etaMinutes(2_000.0, Travel.BOAT))
        assertEquals(1, Teams.etaMinutes(0.0, Travel.VEHICLE))
    }

    @Test fun defaultTravelByTeam() {
        assertEquals(Travel.VEHICLE, Teams.defaultTravel(HelloRole.AMBULANCE))
        assertEquals(Travel.BOAT, Teams.defaultTravel(HelloRole.NDRF))
        assertEquals(Travel.FOOT, Teams.defaultTravel(HelloRole.VOLUNTEER))
    }

    @Test fun teamForPicksMostSpecificFirst() {
        assertEquals(HelloRole.AMBULANCE, Teams.teamFor(setOf(Need.AMBULANCE, Need.MEDICAL, Need.TRAPPED)))
        assertEquals(HelloRole.FIREFIGHTER, Teams.teamFor(setOf(Need.FIRE, Need.MEDICAL)))
        assertEquals(HelloRole.AMBULANCE, Teams.teamFor(setOf(Need.MEDICAL, Need.TRAPPED)))
        assertEquals(HelloRole.NDRF, Teams.teamFor(setOf(Need.TRAPPED)))
        assertEquals(HelloRole.POLICE, Teams.teamFor(setOf(Need.ROAD_BLOCKED)))
        assertNull(Teams.teamFor(setOf(Need.PANIC)))
        assertNull(Teams.teamFor(setOf(Need.WATER_FOOD)))
    }

    @Test fun focusByTeam() {
        assertEquals(setOf(Need.AMBULANCE, Need.MEDICAL), Teams.focus(HelloRole.AMBULANCE))
        assertNull(Teams.focus(HelloRole.RESCUER))
        assertNull(Teams.focus(HelloRole.NEEDS_HELP))
    }

    @Test fun positionsVisibleToTeamsOnly() {
        assertTrue(Teams.canSeePosition(HelloRole.NDRF, HelloRole.NEEDS_HELP, mine = false))
        assertTrue(Teams.canSeePosition(HelloRole.NEEDS_HELP, HelloRole.AMBULANCE, mine = false))
        assertFalse(Teams.canSeePosition(HelloRole.NEEDS_HELP, HelloRole.NEEDS_HELP, mine = false))
        assertFalse(Teams.canSeePosition(HelloRole.VOLUNTEER, HelloRole.NEEDS_HELP, mine = false))
        assertTrue(Teams.canSeePosition(HelloRole.VOLUNTEER, HelloRole.VOLUNTEER, mine = true))
        assertTrue(HelloRole.RESCUER.isTeam && !HelloRole.VOLUNTEER.isTeam)
    }
}
