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
 * Simulation mode (CLAUDE.md 7.5) as a disaster drill: this phone plus 10 virtual phones around it, every one
 * run by the real [Router] over a [VirtualMesh]. Built for demos without spare phones; everything it produces is
 * labelled Simulated by the caller and kept apart from real data.
 *
 * Who is where (metres from the centre: this phone's position at the start, else the venue) and who can hear whom
 * (links), at most 4 hops from this phone:
 *
 *     Arjun (fire) — Farhan (fire at home) — Meena (flood) — Ravi (volunteer) — THIS PHONE — Divya (volunteer)
 *                                            Joseph (road) — Ravi                Divya — Lakshmi (water) — Priya (NDRF)
 *                                            Suresh (police) — Joseph            Divya — Kiran (ambulance)
 *
 * A short script opens the drill (flood, fire, water, an ambulance request, a blocked road, reports, a check-in), then a
 * new incident every 50 s. Each team answers the alerts meant for its type (Teams.teamFor; anything else goes to NDRF)
 * with a travel-time estimate from where it is, drives there on the map (time-lapse, [TIME_LAPSE]× real speed), and
 * when it arrives the person checks in safe. Phones can be switched off and on to show store and forward.
 */
class MeshSimulation(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val wallSeconds: () -> Long,
    /** Where the drill happens: this phone's position when the session starts, else the venue centre. */
    val centerLat: Double = LAT,
    val centerLon: Double = LON,
    latencyMs: Long = 120L,
    /** The offline road network (null until loaded): teams drive along roads when it is there. */
    private val roads: () -> com.lifeline.mesh.routing.RoadMap? = { null },
    /** Places reported blocked right now: teams' routes go around them, and change when a new one is reported. */
    private val blocks: () -> List<com.lifeline.mesh.routing.Block> = { emptyList() },
) {
    /** A virtual phone: name, role, where it stands (metres north/east of the venue centre), battery, origin. */
    data class Spec(val name: String, val role: HelloRole, val northM: Double, val eastM: Double, val battery: Int, val origin: Int)

    /** Something that happens in the drill, at its own place (metres from the venue centre). */
    private data class Incident(val northM: Double, val eastM: Double, val needs: Int, val severity: Int, val people: Int, val note: String)

    companion object {
        const val ME = "me"
        /** Venue centre in Bengaluru (inside the offline map). */
        const val LAT = 12.9716
        const val LON = 77.5946
        /** Teams move this many times faster than their real speed, so a rescue fits in about a minute. */
        const val TIME_LAPSE = 12.0
        private const val TICK_MS = 4_000L

        val SPECS = listOf(
            Spec("Ravi", HelloRole.VOLUNTEER, 150.0, 40.0, 64, 0x5170a1b2),
            Spec("Divya", HelloRole.VOLUNTEER, -350.0, 420.0, 47, 0x5170a2c3),
            Spec("Meena", HelloRole.NEEDS_HELP, 650.0, 450.0, 18, 0x5170c3d4),
            Spec("Joseph", HelloRole.NEEDS_HELP, 1300.0, -200.0, 33, 0x5170c4e5),
            Spec("Farhan", HelloRole.NEEDS_HELP, 500.0, 1300.0, 52, 0x5170c5f6),
            Spec("Lakshmi", HelloRole.NEEDS_HELP, -1000.0, 600.0, 71, 0x5170c607),
            Spec("Kiran", HelloRole.AMBULANCE, -1200.0, -1300.0, 91, 0x5170e5f6),
            Spec("Arjun", HelloRole.FIREFIGHTER, 1800.0, 1500.0, 88, 0x5170e617),
            Spec("Priya", HelloRole.NDRF, -2100.0, 200.0, 95, 0x5170e728),
            Spec("Suresh", HelloRole.POLICE, 1500.0, -1500.0, 80, 0x5170e839),
        )
        val PHONES = SPECS.map { it.name }
        private val LINKS = listOf(
            ME to "Ravi", ME to "Divya", "Ravi" to "Meena", "Ravi" to "Joseph", "Meena" to "Farhan", "Farhan" to "Arjun",
            "Divya" to "Lakshmi", "Lakshmi" to "Priya", "Divya" to "Kiran", "Joseph" to "Suresh",
        )
        private const val T = 1; private const val M = 2; private const val W = 4; private const val F = 8
        private const val R = 16; private const val A = 64

        /** New incidents after the opening script, sent by the nearest virtual phone that is not a team. */
        private val INCIDENTS = listOf(
            Incident(900.0, -700.0, T or M, 3, 2, "Wall collapsed, 2 trapped, one bleeding"),
            Incident(-600.0, -900.0, F, 3, 1, "Electric fire in a shop, smoke"),
            Incident(300.0, 1700.0, M or A, 3, 1, "Pregnant woman needs a hospital"),
            Incident(-1500.0, -300.0, T, 3, 5, "Family of 5 stranded by flood water"),
            Incident(1100.0, 900.0, R, 2, 1, "Bridge washed away, cars stopped"),
            Incident(-200.0, -1600.0, W, 2, 12, "Need food and water for 12"),
        )

    }

    fun latOf(northM: Double) = centerLat + northM / 111_320.0
    fun lonOf(eastM: Double) = centerLon + eastM / (111_320.0 * kotlin.math.cos(Math.toRadians(centerLat)))

    val mesh = VirtualMesh(scope, latencyMs)
    val meTransport: Transport = mesh.transport(ME)

    private val _neighbors = MutableStateFlow<Map<String, Neighbor>>(emptyMap())
    /** This phone's direct links, in the same shape as [NearbyTransport.neighbors]. */
    val neighbors: StateFlow<Map<String, Neighbor>> = _neighbors

    private val _online = MutableStateFlow(PHONES.associateWith { true })
    val online: StateFlow<Map<String, Boolean>> = _online

    /** A rescue a team has taken on: where to go, whose alert it was, how urgent, and when it was accepted. */
    private data class Task(val alertId: Long, val origin: Int, val lat: Double, val lon: Double, val severity: Int, val acceptedAt: Long) {
        fun asHeading(responder: Int) = com.lifeline.mesh.triage.Heading(responder, alertId, lat, lon, 0, acceptedAt, severity)
        /** The way there (road points, or a straight line), the blockages it was planned around, and progress along it. */
        @Volatile var path: List<Pair<Double, Double>>? = null
        @Volatile var pathBlocks: List<com.lifeline.mesh.routing.Block>? = null
        @Volatile var planning = false
        var next = 1
    }

    private inner class Phone(val spec: Spec) : RouterListener {
        val name get() = spec.name
        val router = Router(
            me = spec.origin, transport = mesh.transport(spec.name), scope = scope, listener = this,
            clock = clock, wallSeconds = wallSeconds, peerLabel = ::label,
        ).also {
            it.displayName = spec.name
            it.role = spec.role
            it.battery = spec.battery
        }
        val beacon = HelloBeacon(router)
        @Volatile var lat = latOf(spec.northM)
        @Volatile var lon = lonOf(spec.eastM)
        val tasks = java.util.concurrent.CopyOnWriteArrayList<Task>()
        /** Same order as real teams (Responders.queues): most urgent first, then the one accepted first. */
        private fun queue() = tasks.sortedWith(compareByDescending<Task> { it.severity }.thenBy { it.acceptedAt })
        private var lastPositionAt = 0L
        private var restUntil = 0L

        override fun onPacket(packet: Packet, raw: ByteArray, fromPeer: String?, linkHops: Int, mine: Boolean) {
            if (mine || packet !is Packet.Alert || !spec.role.isTeam) return
            val responder = com.lifeline.mesh.triage.Teams.teamFor(Need.fromMask(packet.needs)) ?: HelloRole.NDRF
            if (responder != spec.role || tasks.any { it.alertId == packet.header.messageId }) return
            scope.launch {
                delay(3_000L + (spec.origin and 0x7) * 300L)
                if (!mesh.isUp(name)) return@launch
                val known = packet.latE6 != 0 || packet.lonE6 != 0
                val travel = com.lifeline.mesh.triage.Teams.defaultTravel(spec.role)
                // Through every open job at least as urgent as this one, then here.
                val ahead = queue().filter { it.severity >= packet.severity }.map { it.lat to it.lon }
                // By road where the map has roads, around reported blockages (in the background: A* is not for the UI thread).
                val eta = if (!known) 20 else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.lifeline.mesh.routing.RoutePlanner.plan(roads(), lat to lon, ahead, packet.latE6 / 1e6 to packet.lonE6 / 1e6,
                        travel, blocks()).minutes
                }
                router.originate(Packet.Ack(router.newHeader(), packet.header.messageId, eta.coerceIn(1, 255), onTheWay(spec.role)))
                if (known) tasks.add(Task(packet.header.messageId, packet.header.origin, packet.latE6 / 1e6, packet.lonE6 / 1e6,
                    packet.severity, clock()))
            }
        }

        /**
         * A team drives towards its most urgent task at time-lapse speed (a new urgent one makes it turn), along the roads
         * of the offline map, around reported blockages (a new blockage makes it plan again from where it is); on arrival
         * the person checks in safe.
         */
        fun tick(now: Long) {
            var moved = false
            val t = queue().firstOrNull()
            if (t != null && now >= restUntil && mesh.isUp(name)) {
                val travel = com.lifeline.mesh.triage.Teams.defaultTravel(spec.role)
                val b = blocks()
                // Plan (or plan again) in the background; keep going on the old path meanwhile.
                if ((t.path == null || t.pathBlocks != b) && !t.planning) {
                    t.planning = true
                    val fromLat = lat; val fromLon = lon
                    scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        val p = com.lifeline.mesh.routing.RoutePlanner.plan(roads(), fromLat to fromLon, emptyList(), t.lat to t.lon, travel, b)
                        t.path = p.points; t.next = 1; t.pathBlocks = b; t.planning = false
                    }
                }
                var stepLeft = travel.kmh / 3.6 * (TICK_MS / 1000.0) * TIME_LAPSE
                val path = t.path
                if (path != null) {
                    while (stepLeft > 0 && t.next < path.size) {
                        val (tLat, tLon) = path[t.next]
                        val d = com.lifeline.mesh.triage.GeoMath.distanceM(lat, lon, tLat, tLon)
                        if (d <= stepLeft) { lat = tLat; lon = tLon; stepLeft -= d; t.next++ }
                        else { val f = stepLeft / d; lat += (tLat - lat) * f; lon += (tLon - lon) * f; stepLeft = 0.0 }
                    }
                    moved = true
                    if (t.next >= path.size && t.pathBlocks == b) {
                        lat = t.lat; lon = t.lon
                        tasks.remove(t)
                        restUntil = now + 10_000 // on the scene for a moment before the next job
                        phones.values.firstOrNull { it.spec.origin == t.origin }?.let { p -> scope.launch { delay(3_000); p.checkIn() } }
                    }
                }
            }
            // Moving teams report every tick; everyone else every 20 s (real phones: 30 s moving, 2 min still).
            if (mesh.isUp(name) && (moved || now - lastPositionAt >= 20_000)) {
                router.sendPosition(Geo.toE6(lat), Geo.toE6(lon), 2)
                lastPositionAt = now
            }
        }

        fun alert(needs: Int, severity: Int, people: Int, note: String, atLat: Double = lat, atLon: Double = lon) {
            if (!mesh.isUp(name)) return
            router.originate(Packet.Alert(router.newHeader(), Geo.toE6(atLat), Geo.toE6(atLon), 2, needs, severity, people, note))
        }

        fun checkIn() {
            if (mesh.isUp(name)) router.originate(Packet.CheckIn(router.newHeader(), Geo.toE6(lat), Geo.toE6(lon), 1, name))
        }

        fun report(kind: ReportType, note: String) {
            if (mesh.isUp(name)) router.originate(Packet.Report(router.newHeader(), Geo.toE6(lat), Geo.toE6(lon), 2, kind, note))
        }
    }

    private fun onTheWay(role: HelloRole) = when (role) {
        HelloRole.AMBULANCE -> "Ambulance on the way"
        HelloRole.FIREFIGHTER -> "Fire engine on the way"
        HelloRole.NDRF -> "Rescue boat on the way"
        HelloRole.POLICE -> "Police on the way"
        else -> "Help on the way"
    }

    private val phones = SPECS.associate { it.name to Phone(it) }
    private var job: Job? = null
    private var tickJob: Job? = null
    private var nextIncident = 0

    fun label(id: String): String = if (id == ME) "This phone" else "$id-${"%08x".format(phones[id]?.spec?.origin ?: 0).takeLast(4)}"

    fun start() {
        mesh.onLinkChange = ::publishNeighbors
        phones.values.forEach { it.beacon.start(scope) }
        LINKS.forEach { (a, b) -> mesh.link(a, b) }
        publishNeighbors()
        tickJob = scope.launch {
            delay(1_500)
            while (isActive) {
                val now = clock()
                phones.values.forEach { it.tick(now) }
                delay(TICK_MS)
            }
        }
        job = scope.launch {
            // The opening script: one of each kind of trouble, spread over the first two minutes.
            delay(8_000); phone("Meena").alert(T or M, 3, 4, "Water rising, 4 on the roof, one elderly")
            delay(15_000); phone("Farhan").alert(F, 3, 3, "Kitchen fire, smoke, 3 inside")
            delay(15_000); phone("Lakshmi").alert(W, 2, 8, "No drinking water for 8 people")
            delay(15_000); phone("Divya").alert(M or A, 3, 1, "Old man fell, head injury")
            delay(15_000); phone("Joseph").alert(T or R, 2, 2, "Tree on the road, car stuck")
            delay(10_000); phone("Divya").report(ReportType.SHELTER_OPEN, "School hall open, 200 beds")
            delay(10_000); phone("Suresh").report(ReportType.ROAD_BLOCKED, "Underpass flooded, use ring road")
            delay(10_000); phone("Ravi").checkIn()
            while (isActive) {
                delay(50_000)
                nextIncident()
            }
        }
    }

    fun stop() {
        job?.cancel()
        tickJob?.cancel()
        phones.values.forEach { it.beacon.stop() }
        mesh.onLinkChange = null
    }

    fun setOnline(name: String, on: Boolean) {
        mesh.setOnline(name, on)
        _online.value = PHONES.associateWith { mesh.isUp(it) }
    }

    /** The next incident from the pool, reported by the nearest virtual phone that is not a team. */
    fun nextIncident() {
        val inc = INCIDENTS[nextIncident++ % INCIDENTS.size]
        val at = latOf(inc.northM) to lonOf(inc.eastM)
        val reporter = phones.values.filter { !it.spec.role.isTeam && mesh.isUp(it.name) }
            .minByOrNull { com.lifeline.mesh.triage.GeoMath.distanceM(it.lat, it.lon, at.first, at.second) } ?: return
        reporter.alert(inc.needs, inc.severity, inc.people, inc.note, at.first, at.second)
    }

    private fun phone(name: String) = phones.getValue(name)

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
