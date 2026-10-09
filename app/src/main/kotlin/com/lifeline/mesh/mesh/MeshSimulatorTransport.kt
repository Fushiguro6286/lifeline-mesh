package com.lifeline.mesh.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-memory radio for the simulator (CLAUDE.md 7.5) and the router tests. Nodes are connected by links you
 * add and remove; a node switched off drops all its links until it comes back. Delivery takes [latencyMs].
 * Pure Kotlin, so the same code runs in unit tests (virtual time) and in the app's simulation mode.
 */
class VirtualMesh(
    private val scope: CoroutineScope,
    private val latencyMs: Long = 20L,
) {
    private val lock = Any()
    private val nodes = LinkedHashMap<String, VirtualTransport>()
    private val links = HashSet<Set<String>>()
    private val online = HashMap<String, Boolean>()

    fun transport(id: String): VirtualTransport = synchronized(lock) {
        nodes.getOrPut(id) { online[id] = true; VirtualTransport(id, this) }
    }

    fun link(a: String, b: String) {
        val nowConnected = synchronized(lock) {
            transport(a); transport(b)
            links.add(setOf(a, b)) && isUp(a) && isUp(b)
        }
        if (nowConnected) notifyConnected(a, b)
    }

    fun unlink(a: String, b: String) {
        val wasConnected = synchronized(lock) { connected(a, b).also { links.remove(setOf(a, b)) } }
        if (wasConnected) notifyDisconnected(a, b)
    }

    /** Switch a phone off or on. Off: every link to it drops. On: links to online neighbors come back. */
    fun setOnline(id: String, on: Boolean) {
        val affected = synchronized(lock) {
            if (isUp(id) == on) return
            val others = links.filter { id in it }.map { (it - id).first() }.filter { isUp(it) }
            online[id] = on
            others
        }
        affected.forEach { other -> if (on) notifyConnected(id, other) else notifyDisconnected(id, other) }
    }

    fun connected(a: String, b: String): Boolean = synchronized(lock) { setOf(a, b) in links && isUp(a) && isUp(b) }

    fun isUp(id: String): Boolean = synchronized(lock) { online[id] ?: false }

    fun linkList(): List<Pair<String, String>> = synchronized(lock) { links.map { it.first() to it.last() } }

    private fun notifyConnected(a: String, b: String) {
        nodes[a]?.listener?.onConnected(b)
        nodes[b]?.listener?.onConnected(a)
    }

    private fun notifyDisconnected(a: String, b: String) {
        nodes[a]?.listener?.onDisconnected(b)
        nodes[b]?.listener?.onDisconnected(a)
    }

    internal fun deliver(from: String, to: String, bytes: ByteArray, onResult: (Boolean) -> Unit) {
        if (!connected(from, to)) {
            onResult(false)
            return
        }
        scope.launch {
            delay(latencyMs)
            if (connected(from, to)) {
                nodes[to]?.listener?.onReceived(from, bytes.copyOf())
                onResult(true)
            } else {
                onResult(false)
            }
        }
    }
}

/** One simulated phone's radio. Peer IDs are the other nodes' IDs. */
class VirtualTransport internal constructor(val id: String, private val mesh: VirtualMesh) : Transport {
    @Volatile internal var listener: Transport.Listener? = null

    override fun setListener(listener: Transport.Listener?) {
        this.listener = listener
    }

    override fun send(peer: String, bytes: ByteArray, onResult: (Boolean) -> Unit) {
        mesh.deliver(id, peer, bytes, onResult)
    }
}
