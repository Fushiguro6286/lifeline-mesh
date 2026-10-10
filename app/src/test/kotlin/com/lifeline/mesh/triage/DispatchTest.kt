package com.lifeline.mesh.triage

import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.Need
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchTest {
    private val lat0 = 12.9716
    private val lon0 = 77.5946
    private val m = 180.0 / (Math.PI * 6_371_000.0)

    private fun team(origin: Int, name: String, role: HelloRole, northM: Double) = DispatchTeam(origin, name, role, lat0 + northM * m, lon0)
    private fun job(id: Long, rank: Int, needs: Set<Need>, severity: Int, northM: Double?, takenBy: Int? = null) =
        DispatchJob(id, rank, needs, severity, northM?.let { lat0 + it * m }, northM?.let { lon0 }, takenBy)

    private val kiran = team(1, "Kiran", HelloRole.AMBULANCE, 0.0)
    private val asha = team(2, "Asha", HelloRole.AMBULANCE, 3_000.0)
    private val arjun = team(3, "Arjun", HelloRole.FIREFIGHTER, 1_000.0)

    @Test fun twoMedicalJobsGoToTwoAmbulancesNotOne() {
        val a = Dispatch.split(listOf(
            job(10, 1, setOf(Need.MEDICAL), 3, 500.0),
            job(11, 2, setOf(Need.MEDICAL), 3, 2_500.0),
        ), listOf(kiran, asha, arjun))
        assertEquals("Kiran", a[0].team!!.name)   // nearest to job 10
        assertEquals("Asha", a[1].team!!.name)    // Kiran is busy; Asha is closer to job 11 anyway
        assertTrue(a.all { it.rightType && !it.taken })
    }

    @Test fun aBusyNearTeamCanLoseToAFreeFarOne() {
        // Both jobs are next to Kiran. Kiran takes the first; for the second, Asha (3 km away, free) is sooner
        // than Kiran after 15 minutes on scene.
        val a = Dispatch.split(listOf(
            job(10, 1, setOf(Need.MEDICAL), 3, 100.0),
            job(11, 2, setOf(Need.MEDICAL), 3, 200.0),
        ), listOf(kiran, asha))
        assertEquals(listOf("Kiran", "Asha"), a.map { it.team!!.name })
        assertTrue(a[1].minutes!! < Dispatch.ON_SCENE_MIN + 1)
    }

    @Test fun mostUrgentFirstAndTheRightTypeOfTeam() {
        val a = Dispatch.split(listOf(
            job(10, 2, setOf(Need.WATER_FOOD), 1, 50.0),   // anyone can go
            job(11, 1, setOf(Need.FIRE), 3, 900.0),        // the fire service
            job(12, 3, setOf(Need.MEDICAL), 3, 60.0),      // urgent: goes before the water job
        ), listOf(kiran, arjun))
        val byId = a.associateBy { it.job.alertId }
        assertEquals("Arjun", byId.getValue(11).team!!.name)
        assertEquals("Kiran", byId.getValue(12).team!!.name)
        // The water job goes to whoever is free soonest: Arjun after the fire, or Kiran after the medical job.
        assertTrue(byId.getValue(10).minutes!! >= Dispatch.ON_SCENE_MIN)
        assertEquals(listOf(10L, 11L, 12L).sorted(), a.map { it.job.alertId }.sorted())
    }

    @Test fun acceptedJobsStayAndKeepTheTeamBusy() {
        val a = Dispatch.split(listOf(
            job(10, 1, setOf(Need.MEDICAL), 3, 100.0, takenBy = 1),  // Kiran already said help is coming
            job(11, 2, setOf(Need.MEDICAL), 3, 150.0),
        ), listOf(kiran, asha))
        assertTrue(a[0].taken); assertEquals("Kiran", a[0].team!!.name)
        assertEquals("Asha", a[1].team!!.name)
    }

    @Test fun noTeamOfTheRightTypeFallsBackToTheNearestTeamAndSaysSo() {
        val a = Dispatch.split(listOf(job(10, 1, setOf(Need.TRAPPED), 3, 800.0)), listOf(kiran, arjun))
        assertFalse(a.single().rightType)
        assertEquals("Arjun", a.single().team!!.name)
    }

    @Test fun noPositionNoTeamsAndTies() {
        val noPos = Dispatch.split(listOf(job(10, 1, setOf(Need.MEDICAL), 3, null)), listOf(kiran, asha)).single()
        assertNull(noPos.minutes); assertEquals("Kiran", noPos.team!!.name)   // tie on 0 busy: lower origin
        assertNull(Dispatch.split(listOf(job(10, 1, setOf(Need.MEDICAL), 3, 0.0)), emptyList()).single().team)
        assertTrue(Dispatch.fits(HelloRole.RESCUER, setOf(Need.FIRE)))
        assertFalse(Dispatch.fits(HelloRole.VOLUNTEER, setOf(Need.WATER_FOOD)))
    }
}
