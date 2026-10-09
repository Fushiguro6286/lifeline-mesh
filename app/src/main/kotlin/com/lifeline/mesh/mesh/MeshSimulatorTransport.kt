package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.Geo
import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.Packet
import com.lifeline.mesh.packet.ReportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
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
    /** Called after any link comes up or goes down. */
    @Volatile var onLinkChange: (() -> Unit)? = null

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
        onLinkChange?.invoke()
    }

    private fun notifyDisconnected(a: String, b: String) {
        nodes[a]?.listener?.onDisconnected(b)
        nodes[b]?.listener?.onDisconnected(a)
        onLinkChange?.invoke()
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

/**
 * Simulation mode (CLAUDE.md 7.5): this phone plus 3 virtual phones, 4 in a line, every one run by the real [Router]:
 *
 *     this phone — Ravi (volunteer) — Meena (needs help) — Kiran (ambulance)
 *
 * Meena sends alerts now and then, Ravi checks in, Kiran replies "help is coming" to every alert (including yours)
 * with a travel-time estimate, then drives towards it (positions every 15 s), and, being a team, echoes pings. Phones can be switched off and on to show store and forward.
 * Everything this produces is labeled Simulated by the caller.
 */
class MeshSimulation(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val wallSeconds: () -> Long,
    latencyMs: Long = 120L,
) {
    companion object {
        const val ME = "me"
        val PHONES = listOf("Ravi", "Meena", "Kiran")
        private val ROLES = mapOf("Ravi" to HelloRole.VOLUNTEER, "Meena" to HelloRole.NEEDS_HELP, "Kiran" to HelloRole.AMBULANCE)
        private val ORIGINS = mapOf("Ravi" to 0x5170a1b2, "Meena" to 0x5170c3d4, "Kiran" to 0x5170e5f6)
        private val BATTERY = mapOf("Ravi" to 64, "Meena" to 18, "Kiran" to 91)
        // Around the venue in Bengaluru; Meena is ~600 m north-east of "me".
        const val LAT = 12.9716
        const val LON = 77.5946
        /** Where each virtual phone stands (degrees from LAT/LON). Kiran, the ambulance, starts ~1.7 km south-west. */
        private val START = mapOf("Ravi" to (0.0015 to 0.0005), "Meena" to (0.004 to 0.004), "Kiran" to (-0.010 to -0.012))
    }

    val mesh = VirtualMesh(scope, latencyMs)
    val meTransport: Transport = mesh.transport(ME)

    private val _neighbors = MutableStateFlow<Map<String, Neighbor>>(emptyMap())
    /** This phone's direct links, in the same shape as [NearbyTransport.neighbors]. */
    val neighbors: StateFlow<Map<String, Neighbor>> = _neighbors

    private val _online = MutableStateFlow(PHONES.associateWith { true })
    val online: StateFlow<Map<String, Boolean>> = _online

    private inner class Phone(val name: String) : RouterListener {
        val router = Router(
            me = ORIGINS.getValue(name), transport = mesh.transport(name), scope = scope, listener = this,
            clock = clock, wallSeconds = wallSeconds, peerLabel = ::label,
        ).also {
            it.displayName = name
            it.role = ROLES.getValue(name)
            it.battery = BATTERY.getValue(name)
        }
        val beacon = HelloBeacon(router)
        var lat = LAT + START.getValue(name).first
        var lon = LON + START.getValue(name).second
        /** Where Kiran is driving to (the last alert he answered), or null. */
        var target: Pair<Double, Double>? = null

        override fun onPacket(packet: Packet, raw: ByteArray, fromPeer: String?, linkHops: Int, mine: Boolean) {
            // Kiran, the ambulance, answers every alert after a short pause, then drives towards it.
            if (name == "Kiran" && !mine && packet is Packet.Alert) {
                scope.launch {
                    delay(4_000)
                    if (!mesh.isUp("Kiran")) return@launch
                    val known = packet.latE6 != 0 || packet.lonE6 != 0
                    val eta = if (!known) 20
                    else com.lifeline.mesh.triage.Teams.etaMinutes(
                        com.lifeline.mesh.triage.GeoMath.distanceM(lat, lon, packet.latE6 / 1e6, packet.lonE6 / 1e6),
                        com.lifeline.mesh.triage.Travel.VEHICLE)
                    router.originate(Packet.Ack(router.newHeader(), packet.header.messageId, eta.coerceIn(1, 255), "Ambulance on the way"))
                    if (known) target = packet.latE6 / 1e6 to packet.lonE6 / 1e6
                }
            }
        }

        /** One position beacon; Kiran first moves ~200 m towards his target (a fast-forwarded drive). */
        fun tickPosition() {
            target?.let { (tl, tn) ->
                val d = com.lifeline.mesh.triage.GeoMath.distanceM(lat, lon, tl, tn)
                if (d < 60) target = null
                else { val f = (200.0 / d).coerceAtMost(1.0); lat += (tl - lat) * f; lon += (tn - lon) * f }
            }
            if (mesh.isUp(name)) router.sendPosition(Geo.toE6(lat), Geo.toE6(lon), 2)
        }
    }

    private val phones = PHONES.associateWith { Phone(it) }
    private var job: Job? = null
    private var positionJob: Job? = null

    fun label(id: String): String = if (id == ME) "This phone" else "$id-${"%08x".format(ORIGINS[id] ?: 0).takeLast(4)}"

    fun start() {
        mesh.onLinkChange = ::publishNeighbors
        phones.values.forEach { it.beacon.start(scope) }
        mesh.link(ME, "Ravi")
        mesh.link("Ravi", "Meena")
        mesh.link("Meena", "Kiran")
        publishNeighbors()
        positionJob = scope.launch {
            delay(2_000)
            while (isActive) {
                phones.values.forEach { it.tickPosition() }
                delay(15_000)
            }
        }
        job = scope.launch {
            delay(8_000)
            var turn = 0
            while (isActive) {
                when (turn % 5) {
                    0 -> sendAlert("Meena")
                    1 -> checkIn("Ravi")
                    2 -> sendAlert("Ravi", severity = 3, people = 1, needs = Need.MEDICAL.bit or Need.AMBULANCE.bit, note = "Old man fell, head injury")
                    3 -> report("Meena")
                    4 -> sendAlert("Meena", severity = 2, people = 2, needs = Need.WATER_FOOD.bit, note = "Need drinking water for 2")
                }
                turn++
                delay(40_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        positionJob?.cancel()
        phones.values.forEach { it.beacon.stop() }
        mesh.onLinkChange = null
    }

    fun setOnline(name: String, on: Boolean) {
        mesh.setOnline(name, on)
        _online.value = PHONES.associateWith { mesh.isUp(it) }
    }

    fun sendAlert(
        name: String,
        severity: Int = 3,
        people: Int = 4,
        needs: Int = Need.TRAPPED.bit or Need.MEDICAL.bit,
        note: String = "Water rising, 4 on the roof, one elderly",
    ) {
        val r = phones[name]?.router ?: return
        if (!mesh.isUp(name)) return
        r.originate(Packet.Alert(r.newHeader(), Geo.toE6(LAT + 0.004), Geo.toE6(LON + 0.004), 2, needs, severity, people, note))
    }

    private fun checkIn(name: String) {
        val r = phones[name]?.router ?: return
        if (!mesh.isUp(name)) return
        r.originate(Packet.CheckIn(r.newHeader(), Geo.toE6(LAT + 0.002), Geo.toE6(LON - 0.001), 1, name))
    }

    private fun report(name: String) {
        val r = phones[name]?.router ?: return
        if (!mesh.isUp(name)) return
        r.originate(Packet.Report(r.newHeader(), Geo.toE6(LAT + 0.003), Geo.toE6(LON + 0.002), 3, ReportType.ROAD_BLOCKED, "Main road flooded"))
    }

    private fun publishNeighbors() {
        val now = System.currentTimeMillis()
        val prev = _neighbors.value
        val next = HashMap<String, Neighbor>()
        for ((a, b) in mesh.linkList()) {
            val other = when (ME) { a -> b; b -> a; else -> continue }
            val up = mesh.connected(ME, other)
            val state = if (up) LinkState.CONNECTED else LinkState.LOST
            val old = prev[other]
            next[other] = if (old != null && old.state == state) old else Neighbor(other, label(other), state, now)
        }
        _neighbors.value = next
    }
}
