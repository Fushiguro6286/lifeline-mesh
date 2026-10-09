package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.HelloRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionBeaconTest {
    private val lat = 12.9716
    private val lon = 77.5946
    private val m = 1.0 / 111_320 // one metre of latitude in degrees

    @Test fun firstFixGoesAtOnce() = assertTrue(PositionBeacon.due(null, lat, lon, 0))

    @Test fun movingSendsEvery30sStillEvery2Min() {
        val sent = PositionBeacon.Sent(lat, lon, 0)
        assertFalse(PositionBeacon.due(sent, lat + 100 * m, lon, 29_000))       // moved, but too soon
        assertTrue(PositionBeacon.due(sent, lat + 100 * m, lon, 30_000))        // moved 100 m, 30 s
        assertFalse(PositionBeacon.due(sent, lat + 10 * m, lon, 60_000))        // 10 m is GPS jitter
        assertTrue(PositionBeacon.due(sent, lat, lon, 120_000))                 // heartbeat
    }

    @Test fun visibilityFollowsTeamsRuleAndAge() {
        fun p(origin: Int, role: HelloRole, at: Long) = PeerPosition(origin, "x", role, lat, lon, 10, 1, at)
        val all = listOf(p(1, HelloRole.NEEDS_HELP, 0), p(2, HelloRole.AMBULANCE, 0), p(3, HelloRole.POLICE, -PositionBeacon.SHOW_MS))
        assertEquals(listOf(2), PositionBeacon.visible(all, HelloRole.NEEDS_HELP, 0).map { it.origin })
        assertEquals(listOf(1, 2), PositionBeacon.visible(all, HelloRole.NDRF, 0).map { it.origin })
    }
}
