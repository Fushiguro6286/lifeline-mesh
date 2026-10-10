package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.Packet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlin.random.Random

/**
 * Test harness: several real [Router]s over a [VirtualMesh], on the test's virtual clock.
 * Each node records everything its router reports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshSimulator(
    private val test: TestScope,
    latencyMs: Long = 20L,
    private val randomFor: (String) -> Random = { name -> Random(name.hashCode()) },
    /** Null = the router's normal random 50–300 ms. */
    private val forwardDelayMs: Long? = null,
) {
    val mesh = VirtualMesh(test.backgroundScope, latencyMs)
    val nodes = LinkedHashMap<String, Node>()

    inner class Node(val name: String, val origin: Int) : RouterListener {
        private val random = randomFor(name)
        val packets = mutableListOf<Received>()
        val events = mutableListOf<MeshEvent>()
        val replies = mutableListOf<Packet.Ack>()
        val roundTrips = mutableListOf<RoundTrip>()
        val receipts = mutableListOf<Receipt>()
        val handedOff = mutableMapOf<Long, Int>()
        var topology: List<TopologyNode> = emptyList()
        val activity = mutableListOf<Activity>()
        var waiting = 0

        val router = Router(
            me = origin,
            transport = mesh.transport(name),
            scope = test.backgroundScope,
            listener = this,
            clock = { test.testScheduler.currentTime },
            wallSeconds = { 1_791_500_000L + test.testScheduler.currentTime / 1000 },
            random = random,
            forwardDelayMs = { forwardDelayMs ?: random.nextLong(50L, 301L) },
            peerLabel = { it },
        ).also { it.displayName = name }

        override fun onPacket(packet: Packet, raw: ByteArray, fromPeer: String?, linkHops: Int, mine: Boolean) {
            if (!mine) packets.add(Received(packet, fromPeer, linkHops, raw.size))
        }
        override fun onEvent(event: MeshEvent) { events.add(event) }
        override fun onReply(ack: Packet.Ack) { replies.add(ack) }
        override fun onRoundTrip(refId: Long, responder: Int, millis: Long, hops: Int) {
            roundTrips.add(RoundTrip(refId, responder, millis, hops))
        }
        override fun onTeamReceipt(refId: Long, team: Int, role: HelloRole, hops: Int) { receipts.add(Receipt(refId, team, role, hops)) }
        override fun onHandedOff(messageId: Long, peers: Int) { handedOff[messageId] = peers }
        override fun onTopology(nodes: List<TopologyNode>) { topology = nodes }
        override fun onLinkActivity(peer: String, packet: Packet, outgoing: Boolean, linkHops: Int) {
            activity.add(Activity(peer, Router.label(packet), outgoing, linkHops))
        }
        override fun onWaiting(count: Int) { waiting = count }

        fun received(type: Class<out Packet>) = packets.filter { type.isInstance(it.packet) }
        fun alerts() = packets.mapNotNull { r -> (r.packet as? Packet.Alert)?.let { r } }
        fun eventsOf(kind: MeshEvent.Kind) = events.filter { it.kind == kind }

        fun sendAlert(severity: Int = 3, people: Int = 5, note: String = "test"): Packet.Alert {
            val p = Packet.Alert(router.newHeader(), 12_971_599, 77_594_566, 2, needs = 3, severity = severity, people = people, note = note)
            router.originate(p)
            return p
        }
    }

    data class Received(val packet: Packet, val from: String?, val linkHops: Int, val bytes: Int)
    data class Activity(val peer: String, val label: String, val outgoing: Boolean, val linkHops: Int)
    data class RoundTrip(val refId: Long, val responder: Int, val millis: Long, val hops: Int)
    data class Receipt(val refId: Long, val team: Int, val role: HelloRole, val hops: Int)

    fun node(name: String, role: HelloRole = HelloRole.NEEDS_HELP): Node {
        val n = Node(name, origin = 0x1000 + nodes.size + 1)
        n.router.role = role
        nodes[name] = n
        return n
    }

    /** Nodes in a line, each linked to the next: line("A","B","C") gives A–B–C. */
    fun line(vararg names: String): List<Node> {
        val list = names.map { nodes[it] ?: node(it) }
        names.toList().zipWithNext().forEach { (a, b) -> mesh.link(a, b) }
        return list
    }

    operator fun get(name: String): Node = nodes.getValue(name)

    fun advance(ms: Long) {
        test.testScheduler.advanceTimeBy(ms)
        test.testScheduler.runCurrent()
    }
}
