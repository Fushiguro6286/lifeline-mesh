package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.Packet
import com.lifeline.mesh.packet.PacketCodec
import com.lifeline.mesh.packet.ReportType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RouterTest {

    // ------------------------------------------------------------------ relay

    @Test fun alertFromAReachesCThroughB() = runTest {
        val sim = MeshSimulator(this)
        val (a, b, c) = sim.line("A", "B", "C")
        sim.advance(1_000)

        val alert = a.sendAlert()
        sim.advance(2_000)

        val atB = b.alerts().single()
        val atC = c.alerts().single()
        assertEquals(alert, atB.packet.let { it as Packet.Alert }.copy(header = alert.header))
        assertEquals(1, atB.linkHops)
        assertEquals(2, atC.linkHops)
        assertEquals("B", atC.from)
        // Forwarded copy has TTL 4, hops 1; body unchanged.
        assertEquals(4, atC.packet.header.ttl)
        assertEquals(1, atC.packet.header.hops)
        assertEquals(alert.note, (atC.packet as Packet.Alert).note)
        assertEquals(1, b.eventsOf(MeshEvent.Kind.FORWARDED).size)
        assertEquals(1, a.eventsOf(MeshEvent.Kind.SENT).size)
        // A and C are not linked, so A never hears C directly; C never sends the alert back to B.
        assertTrue(a.alerts().isEmpty())
    }

    @Test fun duplicatesAreDroppedOnEveryPhone() = runTest {
        // Triangle: C hears A's alert directly and again through B.
        val sim = MeshSimulator(this)
        sim.line("A", "B", "C")
        sim.mesh.link("A", "C")
        sim.advance(1_000)

        sim["A"].sendAlert()
        sim.advance(2_000)

        assertEquals(1, sim["B"].alerts().size)
        assertEquals(1, sim["C"].alerts().size)
        val dupes = sim["B"].eventsOf(MeshEvent.Kind.DUPLICATE).size + sim["C"].eventsOf(MeshEvent.Kind.DUPLICATE).size
        assertTrue("expected at least one duplicate drop, got $dupes", dupes >= 1)
        // A's own alert coming back round the triangle is ignored silently, not stored as received.
        assertTrue(sim["A"].alerts().isEmpty())
    }

    @Test fun sameAlertSentTwiceIsDroppedTheSecondTime() = runTest {
        val sim = MeshSimulator(this)
        val (a, b, c) = sim.line("A", "B", "C")
        sim.advance(1_000)
        val alert = a.sendAlert()
        sim.advance(1_000)
        // A resends the identical bytes (same message ID), like a user tapping send twice.
        val raw = PacketCodec.encode(alert)
        sim.mesh.transport("A").send("B", raw)
        sim.advance(2_000)

        assertEquals(1, b.alerts().size)
        assertEquals(1, c.alerts().size)
        assertEquals(1, b.eventsOf(MeshEvent.Kind.DUPLICATE).size)
    }

    @Test fun ttlStopsTheFlood() = runTest {
        // 8 phones in a line: TTL 5 allows 6 links, so phone 7 (index 6) is the last to get it.
        val sim = MeshSimulator(this)
        val names = (0..7).map { "P$it" }
        val nodes = sim.line(*names.toTypedArray())
        sim.advance(1_000)

        nodes[0].sendAlert()
        sim.advance(5_000)

        for (i in 1..6) {
            val r = nodes[i].alerts().single()
            assertEquals("hops at P$i", i, r.linkHops)
        }
        assertTrue("P7 must not get it", nodes[7].alerts().isEmpty())
        assertEquals(0, nodes[6].alerts().single().packet.header.ttl)
        assertEquals(1, nodes[6].eventsOf(MeshEvent.Kind.TTL_END).size)
    }

    @Test fun helloTtlIsTwo() = runTest {
        val sim = MeshSimulator(this)
        val nodes = sim.line("H0", "H1", "H2", "H3", "H4")
        nodes.forEach { HelloBeacon(it.router).start(backgroundScope) }
        sim.advance(11_000)
        // H0's Hello (TTL 2) reaches H1, H2, H3 and stops there.
        val h0 = nodes[0].origin
        assertTrue(nodes[3].topology.any { it.origin == h0 && it.hops == 3 })
        assertTrue(nodes[4].topology.none { it.origin == h0 })
    }

    // ------------------------------------------------------------------ store and forward

    @Test fun alertWaitsWhileBIsOffAndDeliversWhenBComesBack() = runTest {
        val sim = MeshSimulator(this)
        val (a, b, c) = sim.line("A", "B", "C")
        sim.advance(1_000)
        sim.mesh.setOnline("B", false)
        sim.advance(100)

        val alert = a.sendAlert()
        sim.advance(5_000)
        assertTrue(c.alerts().isEmpty())
        assertTrue(a.eventsOf(MeshEvent.Kind.QUEUED).any { it.packetId == alert.header.messageId })
        assertEquals(null, a.handedOff[alert.header.messageId])

        sim.mesh.setOnline("B", true)
        sim.advance(3_000)

        assertEquals(1, b.alerts().size)
        assertEquals(alert.header.messageId, c.alerts().single().packet.header.messageId)
        assertEquals(2, c.alerts().single().linkHops)
        assertTrue(a.eventsOf(MeshEvent.Kind.SYNC).isNotEmpty())
        assertEquals(1, a.handedOff[alert.header.messageId])
    }

    @Test fun relayHoldsPacketUntilNextPhoneAppears() = runTest {
        val sim = MeshSimulator(this)
        val (a, b, c) = sim.line("A", "B", "C")
        sim.advance(1_000)
        sim.mesh.setOnline("C", false)
        sim.advance(100)

        a.sendAlert()
        sim.advance(2_000)
        assertEquals(1, b.alerts().size)
        assertTrue(b.eventsOf(MeshEvent.Kind.QUEUED).isNotEmpty())

        sim.mesh.setOnline("C", true)
        sim.advance(3_000)
        val atC = c.alerts().single()
        assertEquals(2, atC.linkHops)
        assertEquals(4, atC.packet.header.ttl)
    }

    @Test fun syncSendsOnlyWhatThePeerLacks() = runTest {
        val sim = MeshSimulator(this)
        val (a, b) = sim.line("A", "B")
        sim.advance(1_000)
        a.sendAlert(note = "first")
        sim.advance(1_000)
        sim.mesh.unlink("A", "B")
        sim.advance(100)
        a.sendAlert(note = "second")
        sim.advance(100)

        sim.mesh.link("A", "B")
        sim.advance(2_000)

        assertEquals(listOf("first", "second"), b.alerts().map { (it.packet as Packet.Alert).note })
        assertTrue(b.eventsOf(MeshEvent.Kind.DUPLICATE).isEmpty())
    }

    @Test fun restoredRouterStillDeliversItsQueuedAlert() = runTest {
        // A's app was killed with an unsent alert in Room. A new router restores it and sends it on connect.
        val sim = MeshSimulator(this)
        val b = sim.node("B")
        val a = sim.node("A")
        val queued = Packet.Alert(a.router.newHeader(), 0, 0, 255, 1, 3, 2, "saved")
        a.router.restore(listOf(StoredPacket(PacketCodec.encode(queued), mine = true, ageMs = 60_000)))

        sim.mesh.link("A", "B")
        sim.advance(2_000)
        assertEquals(queued.header.messageId, b.alerts().single().packet.header.messageId)
    }

    @Test fun storedPacketsExpireAfterSixHours() = runTest {
        val sim = MeshSimulator(this)
        val b = sim.node("B")
        val a = sim.node("A")
        val old = Packet.Alert(a.router.newHeader(), 0, 0, 255, 1, 3, 2, "old")
        a.router.restore(listOf(StoredPacket(PacketCodec.encode(old), mine = true, ageMs = 6 * 3_600_000L + 1)))
        sim.mesh.link("A", "B")
        sim.advance(2_000)
        assertTrue(b.alerts().isEmpty())
    }

    // ------------------------------------------------------------------ acks and pings

    @Test fun ackFromCReachesAAsAReply() = runTest {
        val sim = MeshSimulator(this)
        val (a, b, c) = sim.line("A", "B", "C")
        sim.advance(1_000)
        val alert = a.sendAlert()
        sim.advance(2_000)

        c.router.originate(Packet.Ack(c.router.newHeader(), alert.header.messageId, 20, "Boat coming"))
        sim.advance(2_000)

        val reply = a.replies.single()
        assertEquals(alert.header.messageId, reply.refId)
        assertEquals(20, reply.etaMinutes)
        assertEquals("Boat coming", reply.note)
        assertEquals(1, a.eventsOf(MeshEvent.Kind.REPLY).size)
        assertTrue("B relays the ack but it is not B's reply", b.replies.isEmpty())
        assertEquals(1, b.received(Packet.Ack::class.java).size)
    }

    @Test fun pingIsEchoedByRescuerAndRoundTripIsMeasuredOnA() = runTest {
        val sim = MeshSimulator(this, latencyMs = 20)
        sim.node("A")
        sim.node("B")
        sim.node("C", HelloRole.RESCUER)
        sim.line("A", "B", "C")
        sim.advance(1_000)
        val a = sim["A"]; val b = sim["B"]; val c = sim["C"]
        val alert = a.sendAlert()
        sim.advance(2_000)

        a.router.sendPing(alert.header.messageId)
        sim.advance(3_000)

        val fromC = a.roundTrips.single { it.responder == c.origin }
        assertEquals(alert.header.messageId, fromC.refId)
        assertEquals(2, fromC.hops)
        // 4 link crossings at 20 ms each, plus two 50–300 ms forwarding delays at B.
        assertTrue("rtt ${fromC.millis}", fromC.millis in 180L..700L)
        // B got the alert straight from A, so it is a "first receiver" and echoes too, over one hop.
        val fromB = a.roundTrips.single { it.responder == b.origin }
        assertEquals(1, fromB.hops)
        assertTrue(fromB.millis in 40L..100L)
    }

    @Test fun noEchoWithoutRescuerOrFirstReceiver() = runTest {
        val sim = MeshSimulator(this)
        val (a, _, c) = sim.line("A", "B", "C")
        sim.advance(1_000)
        // Ping for an ID nobody has seen: B is not its first receiver, and nobody is a rescuer.
        a.router.sendPing(12345L)
        sim.advance(3_000)
        assertTrue(a.roundTrips.isEmpty())
        assertEquals(1, c.received(Packet.Ping::class.java).size)
    }

    // ------------------------------------------------------------------ hello topology

    @Test fun helloBuildsTopologyBeyondDirectNeighbors() = runTest {
        val sim = MeshSimulator(this)
        sim.node("A"); sim.node("B", HelloRole.VOLUNTEER); sim.node("C", HelloRole.RESCUER)
        val (a, b, c) = sim.line("A", "B", "C")
        sim.nodes.values.forEach { HelloBeacon(it.router).start(backgroundScope) }
        sim.advance(11_000)

        val bNode = a.topology.single { it.origin == b.origin }
        assertEquals(1, bNode.hops)
        assertEquals(HelloRole.VOLUNTEER, bNode.role)
        assertEquals("B", bNode.name)
        assertEquals(setOf(a.origin, c.origin), bNode.neighbors.toSet())

        val cNode = a.topology.single { it.origin == c.origin }
        assertEquals(2, cNode.hops)
        assertEquals(listOf(b.origin), cNode.neighbors)
        assertEquals(HelloRole.RESCUER, cNode.role)
    }

    @Test fun silentPhonesExpireFromTopologyAfter60s() = runTest {
        val sim = MeshSimulator(this)
        val (a, b, c) = sim.line("A", "B", "C")
        val beacons = sim.nodes.values.associate { it.name to HelloBeacon(it.router).also { h -> h.start(backgroundScope) } }
        sim.advance(11_000)
        assertTrue(a.topology.any { it.origin == c.origin })

        beacons.getValue("C").stop()
        sim.mesh.unlink("B", "C")
        sim.advance(75_000)
        assertTrue(a.topology.none { it.origin == c.origin })
        assertTrue(a.topology.any { it.origin == b.origin })
    }

    // ------------------------------------------------------------------ rate limit

    @Test fun moreThanTenPerMinuteFromOneOriginIsDropped() = runTest {
        val sim = MeshSimulator(this)
        val (a, b) = sim.line("A", "B")
        sim.advance(1_000)
        repeat(12) { a.sendAlert(note = "n$it"); sim.advance(500) }
        sim.advance(1_000)

        assertEquals(10, b.alerts().size)
        assertEquals(2, b.eventsOf(MeshEvent.Kind.RATE_LIMITED).size)

        sim.advance(60_000)
        a.sendAlert(note = "later")
        sim.advance(1_000)
        assertEquals(11, b.alerts().size)
    }

    @Test fun duplicatesDoNotUseUpTheRateBudget() = runTest {
        // Dense mesh: B hears every alert from A both directly and via C. Ten alerts must all get through.
        val sim = MeshSimulator(this)
        sim.line("A", "B", "C")
        sim.mesh.link("A", "C")
        sim.advance(1_000)
        repeat(10) { sim["A"].sendAlert(note = "n$it"); sim.advance(1_000) }
        sim.advance(2_000)
        assertEquals(10, sim["B"].alerts().size)
        assertTrue(sim["B"].eventsOf(MeshEvent.Kind.RATE_LIMITED).isEmpty())
    }

    // ------------------------------------------------------------------ priority

    @Test fun forwardingSendsMostUrgentFirst() = runTest {
        // Fixed delays so all forwards become due at the same instant; then only priority decides order.
        val sim = MeshSimulator(this, forwardDelayMs = 50L)
        val (b, c) = sim.line("B", "C")
        val x = sim.node("X")
        sim.advance(1_000)
        val h = { x.router.newHeader().copy(messageId = Random.nextLong()) }
        val batch: List<Packet> = listOf(
            Packet.Hello(h().copy(ttl = 2), 50, HelloRole.VOLUNTEER, emptyList(), "X"),
            Packet.Report(h(), 0, 0, 1, ReportType.ROAD_BLOCKED),
            Packet.CheckIn(h(), 0, 0, 1, "X"),
            Packet.Ping(h(), 1L, false),
            Packet.Alert(h(), 0, 0, 1, 1, 1, 1),
            Packet.Alert(h(), 0, 0, 1, 2, 3, 4),
            Packet.Ack(h(), 9L, 10),
        )
        val before = c.packets.size
        // Pretend they all arrive at B from X at once.
        batch.forEach { b.router.onReceived("X", PacketCodec.encode(it)) }
        sim.advance(1_000)

        val order = c.packets.drop(before).map { it.packet }
            .filter { p -> batch.any { it.header.messageId == p.header.messageId } }
            .map { Router.label(it) + (if (it is Packet.Alert) it.severity.toString() else "") }
        assertEquals(listOf("alert3", "reply", "alert1", "ping", "check-in", "report", "hello"), order)
    }

    // ------------------------------------------------------------------ robustness and delivery status

    @Test fun malformedBytesAreLoggedNotFatal() = runTest {
        val sim = MeshSimulator(this)
        val (a, b) = sim.line("A", "B")
        sim.advance(1_000)
        sim.mesh.transport("A").send("B", byteArrayOf(1, 2, 3))
        sim.advance(500)
        assertEquals("too short", b.eventsOf(MeshEvent.Kind.MALFORMED).single().text.substringAfter("(").substringBefore(")"))
        a.sendAlert()
        sim.advance(1_000)
        assertEquals(1, b.alerts().size)
    }

    @Test fun deliveryStatusCountsNeighborsThatGotMyAlert() = runTest {
        val sim = MeshSimulator(this)
        sim.line("B", "A", "C") // A in the middle with two neighbors
        sim.advance(1_000)
        val alert = sim["A"].sendAlert()
        sim.advance(1_000)
        assertEquals(2, sim["A"].handedOff[alert.header.messageId])
    }

    @Test fun neighborOriginsAreLearnedFromDirectPackets() = runTest {
        val sim = MeshSimulator(this)
        val (a, b, c) = sim.line("A", "B", "C")
        sim.advance(1_000)
        assertEquals(mapOf("A" to a.origin, "C" to c.origin), b.router.neighborOrigins())
    }

    // ------------------------------------------------------------------ live mesh view feeds

    @Test fun linkActivityTracesTheAlertAcrossEachLink() = runTest {
        val sim = MeshSimulator(this)
        val (a, b, c) = sim.line("A", "B", "C")
        sim.advance(1_000)
        a.activity.clear(); b.activity.clear(); c.activity.clear()

        a.sendAlert()
        sim.advance(2_000)

        val alertOnly = { n: MeshSimulator.Node -> n.activity.filter { it.label == "alert" } }
        assertEquals(listOf(MeshSimulator.Activity("B", "alert", true, 1)), alertOnly(a))
        assertEquals(listOf(MeshSimulator.Activity("A", "alert", false, 1), MeshSimulator.Activity("C", "alert", true, 2)), alertOnly(b))
        assertEquals(listOf(MeshSimulator.Activity("B", "alert", false, 2)), alertOnly(c))
    }

    @Test fun waitingCountsStoredPacketsNobodyHasYet() = runTest {
        val sim = MeshSimulator(this)
        val a = sim.node("A")
        sim.node("B")
        a.sendAlert(); a.sendAlert()
        sim.advance(100)
        assertEquals(2, a.waiting)
        assertEquals(2, a.router.waitingCount())

        sim.mesh.link("A", "B")
        sim.advance(2_000)
        assertEquals(0, a.waiting)
    }
}
