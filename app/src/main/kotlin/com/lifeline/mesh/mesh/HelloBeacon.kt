package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One phone in the local network, as last described by its own Hello (CLAUDE.md 7.4). */
data class TopologyNode(
    val origin: Int,
    val name: String,
    val role: HelloRole,
    val battery: Int,
    val neighbors: List<Int>,
    /** Router clock, ms. */
    val lastHeard: Long,
    /** Links between this phone and the node when its last Hello arrived (1 = direct neighbor). */
    val hops: Int,
)

/**
 * Topology table built from received Hellos. Nodes silent for [expiryMs] are dropped.
 * Not thread-safe; the [Router] guards it.
 */
class Topology(private val expiryMs: Long = 60_000L) {
    private val nodes = HashMap<Int, TopologyNode>()

    fun update(hello: Packet.Hello, linkHops: Int, now: Long): TopologyNode {
        val node = TopologyNode(
            origin = hello.header.origin,
            name = hello.name,
            role = hello.role,
            battery = hello.battery,
            neighbors = hello.neighbors,
            lastHeard = now,
            hops = linkHops,
        )
        nodes[node.origin] = node
        return node
    }

    /** Removes silent nodes; returns true if anything was removed. */
    fun expire(now: Long): Boolean = nodes.values.removeAll { now - it.lastHeard >= expiryMs }

    fun snapshot(): List<TopologyNode> = nodes.values.sortedBy { it.origin }

    fun name(origin: Int): String? = nodes[origin]?.name?.takeIf { it.isNotBlank() }
}

/**
 * Every [intervalMs] (10 s) asks the router to broadcast a Hello with this phone's direct neighbors, and to
 * expire stale state. The first Hello goes out immediately.
 */
class HelloBeacon(
    private val router: Router,
    private val intervalMs: Long = 10_000L,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                router.tick()
                router.sendHello()
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
