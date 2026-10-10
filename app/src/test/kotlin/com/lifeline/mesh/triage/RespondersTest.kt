package com.lifeline.mesh.triage

import com.lifeline.mesh.packet.HelloRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RespondersTest {
    private val now = 10_000_000L
    private val min = 60_000L
    private val alerts = mapOf(
        1L to AlertSpot(origin = 0xA, lat = 12.970, lon = 77.590),
        2L to AlertSpot(origin = 0xB, lat = 12.980, lon = 77.600),
        3L to AlertSpot(origin = 0xC, lat = null, lon = null),
    )

    @Test fun headsToTheOldestOpenJob() {
        val acks = listOf(AckSeen(7, 2L, 15, now - 5 * min), AckSeen(7, 1L, 10, now - 8 * min))
        val h = Responders.headings(acks, alerts, emptyList(), now).getValue(7)
        assertEquals(1L, h.alertId)
        assertEquals(12.970, h.lat!!, 1e-9)
        assertEquals(10, h.promisedEta)
    }

    @Test fun aJobIsDoneWhenThatPersonChecksInSafeAfterTheReply() {
        val acks = listOf(AckSeen(7, 1L, 10, now - 8 * min), AckSeen(7, 2L, 15, now - 5 * min))
        // 0xA checked in safe after the reply: the team moves on to alert 2.
        assertEquals(2L, Responders.headings(acks, alerts, listOf(0xA to now - 1 * min), now).getValue(7).alertId)
        // A check-in from before the reply does not close the job.
        assertEquals(1L, Responders.headings(acks, alerts, listOf(0xA to now - 9 * min), now).getValue(7).alertId)
        // Both done: no heading.
        assertNull(Responders.headings(acks, alerts, listOf(0xA to now - min, 0xB to now - min), now)[7])
    }

    @Test fun reachingAJobClosesItAndTheResponderMovesOn() {
        val acks = listOf(AckSeen(7, 1L, 10, now - 8 * min), AckSeen(7, 2L, 15, now - 5 * min))
        val first = Responders.headings(acks, alerts, emptyList(), now)
        assertEquals(1L, first.getValue(7).alertId)
        // 40 m from alert 1: arrived.
        val near = 12.970 + 40 / 111_195.0 to 77.590
        val arrived = Responders.arrivals(first, mapOf(7 to near))
        assertEquals(setOf(7 to 1L), arrived)
        assertEquals(2L, Responders.headings(acks, alerts, emptyList(), now, done = arrived).getValue(7).alertId)
        // 500 m away: not yet.
        assertTrue(Responders.arrivals(first, mapOf(7 to (12.970 + 500 / 111_195.0 to 77.590))).isEmpty())
        // No position known for the responder, or no position for the job: nothing to decide.
        assertTrue(Responders.arrivals(first, emptyMap()).isEmpty())
        val noPos = Responders.headings(listOf(AckSeen(8, 3L, 20, now - min)), alerts, emptyList(), now)
        assertTrue(Responders.arrivals(noPos, mapOf(8 to near)).isEmpty())
    }

    @Test fun oldRepliesAndUnknownAlertsAreIgnored() {
        assertTrue(Responders.headings(listOf(AckSeen(7, 1L, 10, now - Responders.FRESH_MS - 1)), alerts, emptyList(), now).isEmpty())
        assertTrue(Responders.headings(listOf(AckSeen(7, 99L, 10, now - min)), alerts, emptyList(), now).isEmpty())
    }

    @Test fun alertWithoutPositionStillCountsAsTheHeading() {
        val h = Responders.headings(listOf(AckSeen(8, 3L, 20, now - min)), alerts, emptyList(), now).getValue(8)
        assertEquals(3L, h.alertId)
        assertNull(h.lat)
    }

    @Test fun eachResponderHasItsOwnHeading() {
        val acks = listOf(AckSeen(7, 1L, 10, now - 3 * min), AckSeen(8, 2L, 12, now - 2 * min))
        val all = Responders.headings(acks, alerts, emptyList(), now)
        assertEquals(1L, all.getValue(7).alertId)
        assertEquals(2L, all.getValue(8).alertId)
    }

    @Test fun promisedEtaCountsDown() {
        assertEquals(20, Responders.promisedLeft(20, now, now))
        assertEquals(14, Responders.promisedLeft(20, now - 6 * min, now))
        assertEquals(15, Responders.promisedLeft(20, now - 5 * min - 30_000, now)) // rounds up
        assertEquals(0, Responders.promisedLeft(20, now - 25 * min, now))
    }

    @Test fun liveEtaUsesTheRoleSpeedAndGoesViaTheCurrentJob() {
        // 1.2 km straight north by road: 1.2 × 1.3 / 25 km/h = 3.7 min → 4.
        val (d, m) = Responders.liveEta(12.970, 77.590, 12.970 + 1200 / 111_195.0, 77.590, HelloRole.AMBULANCE)
        assertEquals(1200.0, d, 5.0)
        assertEquals(4, m)
        // Same trip via a job 1 km east of the start: longer.
        val via = 12.970 to 77.590 + 1000 / (111_195.0 * kotlin.math.cos(Math.toRadians(12.970)))
        val (d2, m2) = Responders.liveEta(12.970, 77.590, 12.970 + 1200 / 111_195.0, 77.590, HelloRole.AMBULANCE, via)
        assertTrue("via distance $d2", d2 > 2_500)
        assertTrue(m2 > m)
    }
}
