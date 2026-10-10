package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.Header
import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.Packet
import com.lifeline.mesh.packet.ReportType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPolicyTest {
    private val h = Header(1L, 0x1234, 5, 0, 1_000)

    @Test fun modeFollowsBatteryChargingAndTheSwitch() {
        assertEquals(PowerMode.NORMAL, PowerPolicy.mode(21, charging = false, enabled = true))
        assertEquals(PowerMode.SAVER, PowerPolicy.mode(20, charging = false, enabled = true))
        assertEquals(PowerMode.CRITICAL, PowerPolicy.mode(10, charging = false, enabled = true))
        assertEquals(PowerMode.NORMAL, PowerPolicy.mode(5, charging = true, enabled = true))   // charging: work normally
        assertEquals(PowerMode.NORMAL, PowerPolicy.mode(5, charging = false, enabled = false)) // switched off in Settings
        assertEquals(PowerMode.NORMAL, PowerPolicy.modeOf(255))                                 // a Hello's "unknown"
        assertEquals(PowerMode.SAVER, PowerPolicy.modeOf(18))
    }

    @Test fun helloStaysWithinTheSixtySecondExpiry() {
        PowerMode.entries.forEach { assertTrue(PowerPolicy.helloIntervalMs(it) < 60_000L) }
    }

    @Test fun whatEachModePassesOn() {
        val urgent = Packet.Alert(h, 0, 0, 2, 1, 3, 2, "")
        val moderate = Packet.Alert(h, 0, 0, 2, 4, 1, 3, "")
        val ack = Packet.Ack(h, 9L, 10, "")
        val safe = Packet.CheckIn(h, 0, 0, 2, "Asha")
        val report = Packet.Report(h, 0, 0, 2, ReportType.WATER_AVAILABLE, "")
        val ping = Packet.Ping(h, 9L, false)
        val teamPos = Packet.Position(h, 0, 0, 2, HelloRole.AMBULANCE, "Kiran")
        val personPos = Packet.Position(h, 0, 0, 2, HelloRole.NEEDS_HELP, "Asha")
        val all = listOf(urgent, moderate, ack, safe, report, ping, teamPos, personPos)

        assertTrue(all.all { PowerPolicy.relays(PowerMode.NORMAL, it) })
        assertEquals(listOf(urgent, moderate, ack, safe, teamPos, personPos), all.filter { PowerPolicy.relays(PowerMode.SAVER, it) })
        assertEquals(listOf(urgent, ack, safe, teamPos), all.filter { PowerPolicy.relays(PowerMode.CRITICAL, it) })
    }

    @Test fun aCriticalRelayHoldsTheRestAndPassesItOnWhenCharging() = runTest {
        val sim = MeshSimulator(this)
        val (a, b, c) = sim.line("A", "B", "C")
        b.router.setPower(PowerMode.CRITICAL)
        sim.advance(1_000)

        val urgent = a.sendAlert(severity = 3, note = "urgent")
        val moderate = a.sendAlert(severity = 1, note = "moderate")
        sim.advance(2_000)
        // B passes on the urgent alert at once and keeps the other.
        assertEquals(listOf("urgent"), c.alerts().map { (it.packet as Packet.Alert).note })
        assertTrue(b.events.any { it.text.startsWith("Saving battery: holding") })

        // B starts charging: what it held goes out now.
        b.router.setPower(PowerMode.NORMAL)
        sim.advance(2_000)
        assertEquals(setOf(urgent.header.messageId, moderate.header.messageId), c.alerts().map { it.packet.header.messageId }.toSet())
    }

    @Test fun ownPacketsAlwaysGoOut() = runTest {
        val sim = MeshSimulator(this)
        val (a, b) = sim.line("A", "B")
        a.router.setPower(PowerMode.CRITICAL)
        sim.advance(1_000)
        a.sendAlert(severity = 0, note = "mine, not urgent")
        sim.advance(2_000)
        assertEquals(1, b.alerts().size)
        assertFalse(a.events.any { it.text.startsWith("Saving battery") })
    }
}
