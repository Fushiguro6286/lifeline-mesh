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
        CONNECTED, DISCONNECTED, REPLY, ROUND_TRIP, SYNC, RECEIPT,
    }
}

/** A packet crossing one of this phone's links, for the Live Mesh animation. */
data class LinkEvent(
    /** Neighbor endpoint ID, and its origin once known from its own packets. */
    val peer: String,
    val peerOrigin: Int?,
    /** True: this phone handed it to the neighbor. False: this phone received it. */
    val outgoing: Boolean,
    val packet: Packet,
    /** Links travelled when it reached the receiving phone. */
    val linkHops: Int,
    val atMs: Long,
)

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
    /**
     * A rescue team's phone has one of my alerts: it pinged the alert back (team phones do that for every new alert),
     * and its role is known from its Hello or Position beacon. [hops] = links its ping crossed to reach me. Called at
     * most once per alert (the first team), and only for a phone whose beacon says it is a team: nothing is guessed.
     */
    fun onTeamReceipt(refId: Long, team: Int, role: HelloRole, hops: Int) {}
    /** One of my packets has now been received by [peers] different direct neighbors. */
    fun onHandedOff(messageId: Long, peers: Int) {}
    fun onTopology(nodes: List<TopologyNode>) {}
    /**
     * A packet crossed a link: [outgoing] = a neighbor confirmed receiving it from this phone; otherwise this phone
     * received it (first copy only). [linkHops] = links travelled when it arrived at the receiving phone.
     * Hellos and summaries are not reported.
     */
    fun onLinkActivity(peer: String, packet: Packet, outgoing: Boolean, linkHops: Int) {}
    /** My stored packets that no neighbor has received yet (the "N waiting" badge). */
    fun onWaiting(count: Int) {}
}

/** A packet from Room, for rebuilding router state after the app restarts. */
class StoredPacket(val raw: ByteArray, val mine: Boolean, val ageMs: Long, val delivered: Boolean = true)

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
 * Hellos and Positions ("beacons") are deduplicated in their own small cache and are not rate limited, so 6 Hellos
 * and 2 Positions a minute from every phone cannot push real alerts out of the 2,000-entry seen cache or over the
 * limit. Beacons are never stored for store and forward (a 6-hour-old position is wrong, not late) and make no events.
 *
 * Packets fetched from the sync server come in through [onReceived] with the peer [INTERNET]: they are deduplicated,
 * stored and relayed into the local mesh like any other, and the UI marks them "via internet".
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
    /**
     * Measure round trips without anyone pressing "Send ping" (CLAUDE.md 10): ping my alert as soon as a neighbor
     * takes it, and, on a team phone, ping each new alert back towards its sender. At most one automatic ping per
     * [AUTO_PING_GAP_MS], so pings never use up the 10-a-minute budget this phone's alerts and acks need at relays.
     */
    @Volatile var autoPing: Boolean = false

    private val lock = Any()
    private val peers = LinkedHashSet<String>()
    private val peerOrigin = HashMap<String, Int>()
    private val seen = SeenCache()
    private val helloSeen = SeenCache(capacity = 1_024, expiryMs = 300_000L)
    private val rate = RateLimiter()
    private val outbox = Outbox()
    private val topology = Topology()
    private val myIds = HashSet<Long>()
    /** Packets I received straight from their origin: for the "first receiver" ping echo rule. */
    private val directIds = LinkedHashSet<Long>()
    private val pendingPings = HashMap<Long, Long>()
    private var lastAutoPing: Long? = null
    /** Team phone: new alerts still to ping back to their senders (receipts), oldest first, with when they arrived. */
    private val receiptsToSend = ArrayDeque<Pair<Long, Long>>()
    private var lastReceipt: Long? = null
    /** Roles of other phones, from their Hello and Position beacons (for "reached a team"). */
    private val roles = HashMap<Int, HelloRole>()
    /** My alerts a team has confirmed, and team pings for my alerts whose sender's role is not known yet. */
    private val receiptDone = HashSet<Long>()
    private val receiptWaiting = HashMap<Int, MutableList<Pair<Long, Int>>>()
    private val rttDone = HashSet<String>()
    private val handedTo = HashMap<Long, MutableSet<String>>()
    /** Packets at least one neighbor has received, for the waiting count. */
    private val delivered = HashSet<Long>()
    private var lastWaiting = -1
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

    /** Ping for a packet; any rescuer, the packet's first receiver, or its sender echoes it back. */
    fun sendPing(refId: Long): ByteArray = originate(Packet.Ping(newHeader(), refId, echo = false))

    private fun autoPingLocked(refId: Long, now: Long, fx: Effects) {
        val last = lastAutoPing
        if (!autoPing || (last != null && now - last < AUTO_PING_GAP_MS)) return
        lastAutoPing = now
        originateLocked(Packet.Ping(newHeader(), refId, echo = false), fx)
    }

    /**
     * Team phone: ping waiting new alerts back to their senders, at most one every [RECEIPT_GAP_MS] (so a busy team
     * stays well under the 10-a-minute limit relays apply to it); the rest go on later ticks. Too old ones are dropped:
     * the sender has heard nothing for that long, but a late receipt would still be true, so the limit is generous.
     */
    private fun sendReceiptsLocked(now: Long, fx: Effects) {
        while (receiptsToSend.isNotEmpty() && now - receiptsToSend.first().second > RECEIPT_MAX_AGE_MS) receiptsToSend.removeFirst()
        val last = lastReceipt
        if (receiptsToSend.isEmpty() || (last != null && now - last < RECEIPT_GAP_MS)) return
        lastReceipt = now
        originateLocked(Packet.Ping(newHeader(), receiptsToSend.removeFirst().first, echo = false), fx)
    }

    private fun receiptLocked(refId: Long, from: Int, hops: Int, fx: Effects) {
        if (refId in receiptDone) return
        val r = roles[from]
        when {
            r == null -> {
                if (receiptWaiting.size >= 200) receiptWaiting.remove(receiptWaiting.keys.first())
                receiptWaiting.getOrPut(from) { ArrayList() }.add(refId to hops)
            }
            r.isTeam -> {
                receiptDone.add(refId)
                val who = who(from)
                fx.add {
                    listener.onTeamReceipt(refId, from, r, hops)
                    listener.onEvent(MeshEvent(MeshEvent.Kind.RECEIPT, "Your alert reached $who's phone (a rescue team) · ${phones(hops, "hop")}.", refId))
                }
            }
        }
    }

    private fun learnRoleLocked(origin: Int, r: HelloRole, fx: Effects) {
        roles[origin] = r
        if (roles.size > 2_000) roles.remove(roles.keys.first())
        receiptWaiting.remove(origin)?.forEach { (refId, hops) -> receiptLocked(refId, origin, hops, fx) }
    }

    /** My live position (Phase 10); the role and name come from the settings the service applied. */
    fun sendPosition(latE6: Int, lonE6: Int, accuracy: Int): ByteArray =
        originate(Packet.Position(newHeader(), latE6, lonE6, accuracy, role, displayName))

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
        if (beacon(packet)) {
            helloSeen.add(id, now)
        } else {
            seen.add(id, now)
            myIds.add(id)
        }
        if (storable(packet)) outbox.add(id, raw, priority(packet), now)
        if (packet is Packet.Ping && !packet.echo) pendingPings[packet.refId] = now
        val targets = peers.toList()
        fx.add { listener.onPacket(packet, raw, null, 0, mine = true) }
        if (!beacon(packet)) {
            val what = label(packet)
            fx.add {
                listener.onEvent(
                    if (targets.isEmpty()) MeshEvent(MeshEvent.Kind.QUEUED, "Your $what is saved. Waiting for a nearby phone.", id)
                    else MeshEvent(MeshEvent.Kind.SENT, "You sent $what to ${phones(targets.size)}.", id)
                )
            }
        }
        // My own packets go out at once; the random delay is for forwarding only.
        targets.forEach { peer -> fx.add { transport.send(peer, raw) { ok -> onSendResult(raw, id, peer, ok, mine = true) } } }
        checkWaitingLocked(now, fx)
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
        if (h.hops == 0 && h.origin != me && peer != INTERNET) peerOrigin[peer] = h.origin

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
            beacon(p) -> if (!helloSeen.add(id, now)) return
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
        if (linkHops == 1 && !beacon(p) && peer != INTERNET) {
            directIds.add(id)
            if (directIds.size > 2_000) directIds.remove(directIds.first())
        }
        fx.add { listener.onPacket(p, bytes, peer, linkHops, mine = false) }
        if (!beacon(p) && peer != INTERNET) fx.add { listener.onLinkActivity(peer, p, outgoing = false, linkHops = linkHops) }

        when (p) {
            is Packet.Hello -> {
                learnRoleLocked(h.origin, p.role, fx)
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
            is Packet.Position -> learnRoleLocked(h.origin, p.role, fx)
            // A team phone tells each sender their alert reached it (an alert from the internet came another way).
            is Packet.Alert -> if (role.isTeam && peer != INTERNET && autoPing) {
                receiptsToSend.addLast(id to now)
                while (receiptsToSend.size > MAX_RECEIPTS_QUEUED) receiptsToSend.removeFirst()
                sendReceiptsLocked(now, fx)
            }
            is Packet.Ping -> if (!p.echo) {
                // A phone pinging my alert (not an echo of my own ping) holds it: a receipt if it is a team's phone.
                if (p.refId in myIds) receiptLocked(p.refId, h.origin, linkHops, fx)
                // Rescuers, the first receiver, and the packet's own sender (a rescuer pinging it back) answer.
                if (role.isTeam || p.refId in directIds || p.refId in myIds) {
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

        if (!beacon(p)) {
            val what = ownerLabel(p).replaceFirstChar { it.uppercase() }
            val text = if (peer == INTERNET) "$what arrived over the internet." else "$what arrived · ${phones(linkHops, "hop")}."
            fx.add { listener.onEvent(MeshEvent(MeshEvent.Kind.RECEIVED, text, id)) }
        }

        // Forward.
        if (h.ttl == 0) {
            if (!beacon(p)) {
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
        checkWaitingLocked(now, fx)
        // A reply to my own packet has arrived where it was going; "holding" it would only confuse.
        val forMe = p is Packet.Ack && p.refId in myIds
        if (!beacon(p) && !(forMe && targets.isEmpty())) {
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
        ready.forEach { o -> transport.send(o.peer, o.bytes) { ok -> onSendResult(o.bytes, o.id, o.peer, ok, o.mine) } }
    }

    private fun onSendResult(bytes: ByteArray, id: Long, peer: String, ok: Boolean, mine: Boolean) {
        if (!ok) return
        val fx = Effects()
        val packet = (PacketCodec.decode(bytes) as? DecodeResult.Ok)?.packet
        synchronized(lock) {
            if (delivered.add(id)) checkWaitingLocked(clock(), fx)
            if (mine && id in myIds) {
                val set = handedTo.getOrPut(id) { HashSet() }
                if (set.add(peer)) {
                    val count = set.size
                    fx.add { listener.onHandedOff(id, count) }
                    // My alert just left this phone: measure how long a round trip through the mesh takes.
                    if (count == 1 && packet is Packet.Alert) autoPingLocked(id, clock(), fx)
                }
            }
        }
        if (packet != null && !beacon(packet) && packet !is Packet.Summary) {
            fx.add { listener.onLinkActivity(peer, packet, outgoing = true, linkHops = packet.header.hops + 1) }
        }
        fx.run()
    }

    /**
     * My own stored packets that no neighbor has received yet. Other phones' packets a relay holds are not counted:
     * at the end of a line they would wait for nobody, and the badge would look like my alert was stuck.
     */
    private fun waitingLocked(now: Long) = outbox.live(now).count { it.id in myIds && it.id !in delivered }

    private fun checkWaitingLocked(now: Long, fx: Effects) {
        val n = waitingLocked(now)
        if (n != lastWaiting) {
            lastWaiting = n
            fx.add { listener.onWaiting(n) }
        }
    }

    fun waitingCount(): Int = synchronized(lock) { waitingLocked(clock()) }

    // ------------------------------------------------------------------ housekeeping

    /** Expire silent phones, old pings and old stored packets. Called by [HelloBeacon] every 10 s. */
    fun tick() {
        val fx = Effects()
        synchronized(lock) {
            val now = clock()
            outbox.prune(now)
            pendingPings.values.removeAll { now - it > 5 * 60_000L }
            sendReceiptsLocked(now, fx)
            checkWaitingLocked(now, fx)
            if (topology.expire(now)) {
                val snap = topology.snapshot()
                fx.add { listener.onTopology(snap) }
            }
        }
        fx.run()
    }

    /** Rebuilds seen IDs, my IDs and the outbox from Room after a restart. */
    fun restore(stored: List<StoredPacket>) = synchronized(lock) {
        val now = clock()
        for (s in stored) {
            val p = (PacketCodec.decode(s.raw) as? DecodeResult.Ok)?.packet ?: continue
            if (beacon(p) || p is Packet.Summary) continue
            val id = p.header.messageId
            val at = now - s.ageMs
            seen.add(id, now, seenAt = at)
            if (s.mine) myIds.add(id)
            if (s.delivered) delivered.add(id)
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

    private fun who(origin: Int): String =
        if (origin == com.lifeline.mesh.packet.Warnings.ORIGIN) "the rescue server"
        else topology.name(origin) ?: "%08x".format(origin).takeLast(4)

    private fun ownerLabel(p: Packet): String =
        if (p.header.origin == me) "your ${label(p)}" else "${who(p.header.origin)}'s ${label(p)}"

    /** Collects listener and transport calls so they run after the lock is released. */
    private class Effects {
        private val list = ArrayList<() -> Unit>()
        fun add(f: () -> Unit) { list.add(f) }
        fun run() = list.forEach { it() }
    }

    companion object {
        /** Automatic pings: at most one per 30 s from this phone (see [autoPing]). */
        const val AUTO_PING_GAP_MS = 30_000L
        /** Team receipts (one ping per new alert): at most one per 15 s, up to 20 waiting, each kept 10 minutes. */
        const val RECEIPT_GAP_MS = 15_000L
        const val MAX_RECEIPTS_QUEUED = 20
        const val RECEIPT_MAX_AGE_MS = 10 * 60_000L

        /** 0 = most urgent. Severity-3 alerts, acks and severe warnings, then alerts, pings, check-ins, reports, hellos. */
        fun priority(p: Packet): Int = when (p) {
            is Packet.Alert -> if (p.severity >= 3) 0 else 1
            is Packet.Warning -> if (p.level >= 2) 0 else 1
            is Packet.Ack -> 0
            is Packet.Summary -> 0
            is Packet.Ping -> 2
            is Packet.CheckIn -> 3
            is Packet.Report -> 4
            is Packet.Hello -> 5
            is Packet.Position -> 5
        }

        /** Kept for store and forward. Pings and hellos only matter live. */
        /** Pseudo-peer for packets fetched from the sync server. */
        const val INTERNET = "internet"

        /** Hello and Position: own dedup cache, no rate limit, no events, no store and forward. */
        fun beacon(p: Packet): Boolean = p is Packet.Hello || p is Packet.Position

        fun storable(p: Packet): Boolean =
            p is Packet.Alert || p is Packet.CheckIn || p is Packet.Ack || p is Packet.Report || p is Packet.Warning

        fun label(p: Packet): String = when (p) {
            is Packet.Alert -> "alert"
            is Packet.CheckIn -> "check-in"
            is Packet.Ack -> "reply"
            is Packet.Report -> "report"
            is Packet.Ping -> if (p.echo) "ping echo" else "ping"
            is Packet.Hello -> "hello"
            is Packet.Position -> "position"
            is Packet.Warning -> when { p.drill -> "drill warning"; p.forecast -> "weather warning"; else -> "official warning" }
            is Packet.Summary -> "summary"
        }

        private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
        private fun phones(n: Int, word: String = "phone") = "$n ${plural(n, word)}"
    }
}
