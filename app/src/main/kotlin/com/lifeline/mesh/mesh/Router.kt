package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.DecodeResult
import com.lifeline.mesh.packet.Header
import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.Packet
import com.lifeline.mesh.packet.PacketCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** One line for the event timeline. [text] is a plain sentence. */
data class MeshEvent(val kind: Kind, val text: String, val packetId: Long? = null) {
    enum class Kind {
        SENT, QUEUED, RECEIVED, FORWARDED, DUPLICATE, RATE_LIMITED, MALFORMED, TTL_END,
        CONNECTED, DISCONNECTED, REPLY, ROUND_TRIP, SYNC,
    }
}

/**
 * What the router reports. Called outside the router's lock, on whatever thread delivered the packet;
 * implementations must not block.
 */
interface RouterListener {
    /** A new packet to store: one I created ([mine], [fromPeer] null) or one received for the first time. */
    fun onPacket(packet: Packet, raw: ByteArray, fromPeer: String?, linkHops: Int, mine: Boolean) {}
    fun onEvent(event: MeshEvent) {}
    /** An Ack for one of my packets arrived ("Help is coming"). */
    fun onReply(ack: Packet.Ack) {}
    /** Ping echo for one of my pings: measured round trip on this phone's clock only. */
    fun onRoundTrip(refId: Long, responder: Int, millis: Long, hops: Int) {}
    /** One of my packets has now been received by [peers] different direct neighbors. */
    fun onHandedOff(messageId: Long, peers: Int) {}
    fun onTopology(nodes: List<TopologyNode>) {}
}

/** A packet from Room, for rebuilding router state after the app restarts. */
class StoredPacket(val raw: ByteArray, val mine: Boolean, val ageMs: Long)

/**
 * Controlled flooding (CLAUDE.md 7.2) with store and forward (7.3) and Hello topology (7.4).
 * Pure Kotlin: everything outside is reached through [Transport] and [RouterListener].
 *
 * Order on receive: malformed → drop; Summary → sync; my own packet coming back → ignore;
 * duplicate → drop; rate limit → drop; then store, handle by type, and forward with TTL − 1, hops + 1
 * to every other neighbor after a random 50–300 ms delay, most urgent first.
 *
 * Duplicates are checked before the rate limit (spec lists the rate limit first): otherwise every extra copy
 * of one packet, arriving through a different neighbor, would use up the origin's 10-per-minute budget.
 * Hellos are deduplicated in their own small cache and are not rate limited, so 6 Hellos a minute from every
 * phone cannot push real alerts out of the 2,000-entry seen cache or over the limit.
 *
 * Thread-safe: callbacks may come from radio threads and the UI at the same time.
 */
class Router(
    val me: Int,
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val listener: RouterListener,
    /** Monotonic milliseconds (elapsedRealtime on a phone, virtual time in tests). */
    private val clock: () -> Long,
    /** Unix seconds for packet headers (sender clock; never compared across phones). */
    private val wallSeconds: () -> Long,
    private val random: Random = Random.Default,
    /** Delay before forwarding (CLAUDE.md 7.2: random 50–300 ms). Fixed only in tests. */
    private val forwardDelayMs: () -> Long = { random.nextLong(50L, 301L) },
    /** Human label for a neighbor endpoint in events, e.g. "Ravi-3fa1". */
    private val peerLabel: (String) -> String = { it },
) : Transport.Listener {

    @Volatile var role: HelloRole = HelloRole.NEEDS_HELP
    @Volatile var displayName: String = ""
    /** 0–100, or 255 when unknown. */
    @Volatile var battery: Int = 255

    private val lock = Any()
    private val peers = LinkedHashSet<String>()
    private val peerOrigin = HashMap<String, Int>()
    private val seen = SeenCache()
    private val helloSeen = SeenCache(capacity = 512, expiryMs = 120_000L)
    private val rate = RateLimiter()
    private val outbox = Outbox()
    private val topology = Topology()
    private val myIds = HashSet<Long>()
    /** Packets I received straight from their origin: for the "first receiver" ping echo rule. */
    private val directIds = LinkedHashSet<Long>()
    private val pendingPings = HashMap<Long, Long>()
    private val rttDone = HashSet<String>()
    private val handedTo = HashMap<Long, MutableSet<String>>()
    private val queue = ArrayList<Outgoing>()
    private var seq = 0L

    private class Outgoing(
        val peer: String, val bytes: ByteArray, val priority: Int, val readyAt: Long,
        val seq: Long, val id: Long, val mine: Boolean,
    )

    init {
        transport.setListener(this)
    }

    // ------------------------------------------------------------------ state for the UI

    fun connectedPeers(): List<String> = synchronized(lock) { peers.toList() }
    /** Direct neighbors whose origin is known (learned from packets they sent themselves). */
    fun neighborOrigins(): Map<String, Int> = synchronized(lock) { peerOrigin.filterKeys { it in peers } }
    fun topologySnapshot(): List<TopologyNode> = synchronized(lock) { topology.snapshot() }
    fun outboxSize(): Int = synchronized(lock) { outbox.live(clock()).size }

    // ------------------------------------------------------------------ sending

    fun newHeader(ttl: Int = PacketCodec.TTL_DEFAULT): Header {
        var id = 0L
        while (id == 0L) id = random.nextLong()
        return Header(messageId = id, origin = me, ttl = ttl, hops = 0, time = wallSeconds().coerceIn(0, 0xFFFF_FFFFL))
    }

    /** Sends a packet this phone created (header from [newHeader]). Returns the encoded bytes. */
    fun originate(packet: Packet): ByteArray {
        val fx = Effects()
        val raw = synchronized(lock) { originateLocked(packet, fx) }
        fx.run()
        return raw
    }

    /** Ping for one of my packets; any rescuer (or the packet's first receiver) echoes it back. */
    fun sendPing(refId: Long): ByteArray = originate(Packet.Ping(newHeader(), refId, echo = false))

    fun sendHello() {
        val hello = synchronized(lock) {
            Packet.Hello(
                header = newHeader(PacketCodec.TTL_HELLO),
                battery = battery.coerceIn(0, 255),
                role = role,
                neighbors = peerOrigin.filterKeys { it in peers }.values.distinct().take(PacketCodec.MAX_HELLO_NEIGHBORS),
                name = displayName,
            )
        }
        originate(hello)
    }

    private fun originateLocked(packet: Packet, fx: Effects): ByteArray {
        val raw = PacketCodec.encode(packet)
        val now = clock()
        val id = packet.header.messageId
        if (packet is Packet.Hello) {
            helloSeen.add(id, now)
        } else {
            seen.add(id, now)
            myIds.add(id)
        }
        if (storable(packet)) outbox.add(id, raw, priority(packet), now)
        if (packet is Packet.Ping && !packet.echo) pendingPings[packet.refId] = now
        val targets = peers.toList()
        fx.add { listener.onPacket(packet, raw, null, 0, mine = true) }
        if (packet !is Packet.Hello) {
            val what = label(packet)
            fx.add {
                listener.onEvent(
                    if (targets.isEmpty()) MeshEvent(MeshEvent.Kind.QUEUED, "Your $what is saved. Waiting for a nearby phone.", id)
                    else MeshEvent(MeshEvent.Kind.SENT, "You sent $what to ${phones(targets.size)}.", id)
                )
            }
        }
        // My own packets go out at once; the random delay is for forwarding only.
        targets.forEach { peer -> fx.add { transport.send(peer, raw) { ok -> onSendResult(id, peer, ok, mine = true) } } }
        return raw
    }

    // ------------------------------------------------------------------ Transport.Listener

    override fun onConnected(peer: String) {
        val summary = synchronized(lock) {
            peers.add(peer)
            PacketCodec.encode(PeerSync.summary(newHeader(0), seen, clock()))
        }
        listener.onEvent(MeshEvent(MeshEvent.Kind.CONNECTED, "Connected to ${peerLabel(peer)}."))
        transport.send(peer, summary)
        // Tell the new neighbor who we are right away, so it can map this link to our origin.
        sendHello()
    }

    override fun onDisconnected(peer: String) {
        synchronized(lock) {
            peers.remove(peer)
            peerOrigin.remove(peer)
            queue.removeAll { it.peer == peer }
        }
        listener.onEvent(MeshEvent(MeshEvent.Kind.DISCONNECTED, "Lost the link to ${peerLabel(peer)}."))
    }

    override fun onReceived(peer: String, bytes: ByteArray) {
        val packet = when (val r = PacketCodec.decode(bytes)) {
            is DecodeResult.Malformed -> {
                listener.onEvent(MeshEvent(MeshEvent.Kind.MALFORMED, "Dropped a damaged packet from ${peerLabel(peer)} (${r.reason})."))
                return
            }
            is DecodeResult.Ok -> r.packet
        }
        val fx = Effects()
        synchronized(lock) { handleLocked(peer, packet, bytes, fx) }
        fx.run()
    }

    private fun handleLocked(peer: String, p: Packet, bytes: ByteArray, fx: Effects) {
        val now = clock()
        val h = p.header
        val id = h.messageId
        if (h.hops == 0 && h.origin != me) peerOrigin[peer] = h.origin

        when {
            p is Packet.Summary -> {
                val missing = PeerSync.missing(p, outbox, now)
                missing.forEach { enqueue(peer, it.bytes, it.priority, it.id, mine = it.id in myIds, now, fx) }
                if (missing.isNotEmpty()) {
                    fx.add { listener.onEvent(MeshEvent(MeshEvent.Kind.SYNC, "Sent ${missing.size} stored ${plural(missing.size, "packet")} to ${peerLabel(peer)}.")) }
                }
                return
            }
            h.origin == me -> return // one of mine came back around the mesh
            p is Packet.Hello -> if (!helloSeen.add(id, now)) return
            else -> {
                if (seen.contains(id, now)) {
                    val what = ownerLabel(p)
                    fx.add { listener.onEvent(MeshEvent(MeshEvent.Kind.DUPLICATE, "Dropped a copy of $what (already seen).", id)) }
                    return
                }
                if (!rate.allow(h.origin, now)) {
                    val who = who(h.origin)
                    fx.add { listener.onEvent(MeshEvent(MeshEvent.Kind.RATE_LIMITED, "Dropped a ${label(p)} from $who: more than 10 a minute.", id)) }
                    return
                }
                seen.add(id, now)
            }
        }

        val linkHops = h.hops + 1
        if (linkHops == 1 && p !is Packet.Hello) {
            directIds.add(id)
            if (directIds.size > 2_000) directIds.remove(directIds.first())
        }
        fx.add { listener.onPacket(p, bytes, peer, linkHops, mine = false) }

        when (p) {
            is Packet.Hello -> {
                topology.update(p, linkHops, now)
                val snap = topology.snapshot()
                fx.add { listener.onTopology(snap) }
            }
            is Packet.Ack -> if (p.refId in myIds) {
                val who = who(h.origin)
                fx.add {
                    listener.onReply(p)
                    listener.onEvent(MeshEvent(MeshEvent.Kind.REPLY, "Reply from $who: help is coming, ETA ${p.etaMinutes} min.", id))
                }
            }
            is Packet.Ping -> if (!p.echo) {
                if (role == HelloRole.RESCUER || p.refId in directIds) {
                    originateLocked(Packet.Ping(newHeader(), p.refId, echo = true), fx)
                }
            } else {
                val sentAt = pendingPings[p.refId]
                if (sentAt != null && rttDone.add("${p.refId}:${h.origin}:$sentAt")) {
                    val ms = now - sentAt
                    val who = who(h.origin)
                    fx.add {
                        listener.onRoundTrip(p.refId, h.origin, ms, linkHops)
                        listener.onEvent(MeshEvent(MeshEvent.Kind.ROUND_TRIP, "Round trip to $who: $ms ms over ${phones(linkHops, "hop")}.", id))
                    }
                }
            }
            else -> Unit
        }

        if (p !is Packet.Hello) {
            val what = ownerLabel(p)
            fx.add { listener.onEvent(MeshEvent(MeshEvent.Kind.RECEIVED, "${what.replaceFirstChar { it.uppercase() }} arrived · ${phones(linkHops, "hop")}.", id)) }
        }

        // Forward.
        if (h.ttl == 0) {
            if (p !is Packet.Hello) {
                val what = ownerLabel(p)
                fx.add { listener.onEvent(MeshEvent(MeshEvent.Kind.TTL_END, "${what.replaceFirstChar { it.uppercase() }} reached its hop limit; not passed on.", id)) }
            }
            return
        }
        val fwd = PacketCodec.forwardBytes(bytes)
        val prio = priority(p)
        if (storable(p)) outbox.add(id, fwd, prio, now)
        val targets = peers.filter { it != peer }
        targets.forEach { enqueue(it, fwd, prio, id, mine = false, now, fx) }
        if (p !is Packet.Hello) {
            val what = ownerLabel(p)
            fx.add {
                listener.onEvent(
                    if (targets.isEmpty()) MeshEvent(MeshEvent.Kind.QUEUED, "Holding $what until another phone is in range.", id)
                    else MeshEvent(MeshEvent.Kind.FORWARDED, "Passing $what on to ${phones(targets.size)}.", id)
                )
            }
        }
    }

    // ------------------------------------------------------------------ forwarding queue

    private fun enqueue(peer: String, bytes: ByteArray, priority: Int, id: Long, mine: Boolean, now: Long, fx: Effects) {
        val delayMs = forwardDelayMs()
        queue.add(Outgoing(peer, bytes, priority, now + delayMs, seq++, id, mine))
        fx.add {
            scope.launch {
                delay(delayMs)
                flush()
            }
        }
    }

    /** Sends everything that is due, most urgent first (CLAUDE.md 7.2 step 5). */
    private fun flush() {
        val ready = synchronized(lock) {
            val now = clock()
            val due = queue.filter { it.readyAt <= now }
            queue.removeAll(due.toSet())
            due.filter { it.peer in peers }.sortedWith(compareBy<Outgoing> { it.priority }.thenBy { it.seq })
        }
        ready.forEach { o -> transport.send(o.peer, o.bytes) { ok -> onSendResult(o.id, o.peer, ok, o.mine) } }
    }

    private fun onSendResult(id: Long, peer: String, ok: Boolean, mine: Boolean) {
        if (!ok || !mine) return
        val count = synchronized(lock) {
            if (id !in myIds) return
            val set = handedTo.getOrPut(id) { HashSet() }
            if (!set.add(peer)) return
            set.size
        }
        listener.onHandedOff(id, count)
    }

    // ------------------------------------------------------------------ housekeeping

    /** Expire silent phones, old pings and old stored packets. Called by [HelloBeacon] every 10 s. */
    fun tick() {
        val snap = synchronized(lock) {
            val now = clock()
            outbox.prune(now)
            pendingPings.values.removeAll { now - it > 5 * 60_000L }
            if (topology.expire(now)) topology.snapshot() else null
        }
        if (snap != null) listener.onTopology(snap)
    }

    /** Rebuilds seen IDs, my IDs and the outbox from Room after a restart. */
    fun restore(stored: List<StoredPacket>) = synchronized(lock) {
        val now = clock()
        for (s in stored) {
            val p = (PacketCodec.decode(s.raw) as? DecodeResult.Ok)?.packet ?: continue
            if (p is Packet.Hello || p is Packet.Summary) continue
            val id = p.header.messageId
            val at = now - s.ageMs
            seen.add(id, now, seenAt = at)
            if (s.mine) myIds.add(id)
            if (!storable(p)) continue
            val bytes = when {
                s.mine -> s.raw
                p.header.ttl > 0 -> PacketCodec.forwardBytes(s.raw)
                else -> continue
            }
            outbox.add(id, bytes, priority(p), now, storedAt = at)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun who(origin: Int): String = topology.name(origin) ?: "%08x".format(origin).takeLast(4)

    private fun ownerLabel(p: Packet): String =
        if (p.header.origin == me) "your ${label(p)}" else "${who(p.header.origin)}'s ${label(p)}"

    /** Collects listener and transport calls so they run after the lock is released. */
    private class Effects {
        private val list = ArrayList<() -> Unit>()
        fun add(f: () -> Unit) { list.add(f) }
        fun run() = list.forEach { it() }
    }

    companion object {
        /** 0 = most urgent. Severity-3 alerts and acks, then alerts, pings, check-ins, reports, hellos. */
        fun priority(p: Packet): Int = when (p) {
            is Packet.Alert -> if (p.severity >= 3) 0 else 1
            is Packet.Ack -> 0
            is Packet.Summary -> 0
            is Packet.Ping -> 2
            is Packet.CheckIn -> 3
            is Packet.Report -> 4
            is Packet.Hello -> 5
        }

        /** Kept for store and forward. Pings and hellos only matter live. */
        fun storable(p: Packet): Boolean =
            p is Packet.Alert || p is Packet.CheckIn || p is Packet.Ack || p is Packet.Report

        fun label(p: Packet): String = when (p) {
            is Packet.Alert -> "alert"
            is Packet.CheckIn -> "check-in"
            is Packet.Ack -> "reply"
            is Packet.Report -> "report"
            is Packet.Ping -> if (p.echo) "ping echo" else "ping"
            is Packet.Hello -> "hello"
            is Packet.Summary -> "summary"
        }

        private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
        private fun phones(n: Int, word: String = "phone") = "$n ${plural(n, word)}"
    }
}
