package com.lifeline.mesh

import com.lifeline.mesh.data.AppDatabase
import com.lifeline.mesh.data.EventEntity
import com.lifeline.mesh.data.Language
import com.lifeline.mesh.data.MetricEntity
import com.lifeline.mesh.data.PacketEntity
import com.lifeline.mesh.data.Role
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.data.SettingsStore
import com.lifeline.mesh.data.ThemeMode
import com.lifeline.mesh.data.TopologyEntity
import com.lifeline.mesh.data.toEntity
import com.lifeline.mesh.mesh.LinkEvent
import com.lifeline.mesh.mesh.MeshEvent
import com.lifeline.mesh.mesh.MeshSimulation
import com.lifeline.mesh.mesh.MeshHealth
import com.lifeline.mesh.mesh.Neighbor
import com.lifeline.mesh.mesh.Router
import com.lifeline.mesh.packet.Geo
import com.lifeline.mesh.packet.Header
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.ReportType
import com.lifeline.mesh.packet.Packet
import com.lifeline.mesh.packet.PacketCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * The single place the UI reads app state from: settings, Room tables, and live mesh state that
 * [com.lifeline.mesh.mesh.MeshService] publishes while it runs.
 */
class AppRepository(
    val database: AppDatabase,
    private val settingsStore: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Null until the settings file has been read once. */
    val settings: StateFlow<Settings?> =
        settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, null)

    fun setDisplayName(name: String) = scope.launch { settingsStore.setDisplayName(name) }
    fun setRole(role: Role) = scope.launch { settingsStore.setRole(role) }
    fun setLanguage(language: Language) = scope.launch { settingsStore.setLanguage(language) }
    fun setTheme(mode: ThemeMode) = scope.launch { settingsStore.setTheme(mode) }
    fun setSimulationMode(on: Boolean) = scope.launch { settingsStore.setSimulationMode(on) }
    fun setDemoMode(on: Boolean) = scope.launch { settingsStore.setDemoMode(on) }
    fun setReadAloud(on: Boolean) = scope.launch { settingsStore.setReadAloud(on) }
    fun setFallDetection(on: Boolean) = scope.launch { settingsStore.setFallDetection(on) }
    fun setWatchVitals(on: Boolean) = scope.launch { settingsStore.setWatchVitals(on) }
    /** Watch vitals: when Health Connect was last read and how many readings came back (Settings shows it). */
    val lastVitalsCheck = MutableStateFlow<Pair<Long, Int>?>(null)
    fun setServerUrl(url: String) = scope.launch { settingsStore.setServerUrl(url) }
    fun setPhone(phone: String) = scope.launch { settingsStore.setPhone(phone) }
    fun setTravel(travel: com.lifeline.mesh.triage.Travel) = scope.launch { settingsStore.setTravel(travel) }
    val lastUpload: StateFlow<Pair<Long, Int>?> = settingsStore.lastUpload.stateIn(scope, SharingStarted.Eagerly, null)

    /** Profiles on this phone. Password hashing runs off the main thread. */
    fun createProfile(password: String) = scope.launch { settingsStore.createProfile(password) }
    suspend fun signIn(profileId: String, password: String): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.Default) { settingsStore.signIn(profileId, password) }
    fun signOut() = scope.launch { settingsStore.signOut() }

    // ------------------------------------------------------------------ Room

    /** Simulation mode decides which rows the UI sees: real and simulated data are never shown together. */
    private val simulated = settings.map { it?.simulationMode ?: false }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val packets: StateFlow<List<PacketEntity>> =
        simulated.flatMapLatest { database.packets().observe(it) }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    @OptIn(ExperimentalCoroutinesApi::class)
    val events: StateFlow<List<EventEntity>> =
        simulated.flatMapLatest { database.events().observe(it) }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    @OptIn(ExperimentalCoroutinesApi::class)
    val metrics: StateFlow<List<MetricEntity>> =
        simulated.flatMapLatest { database.metrics().observe(it) }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    /** Holds the current session only (cleared when the mesh stops or the mode changes). */
    val topology: StateFlow<List<TopologyEntity>> = database.topology().observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    // ------------------------------------------------------------------ live mesh state (written by MeshService)

    val meshRunning = MutableStateFlow(false)
    val neighbors = MutableStateFlow<List<Neighbor>>(emptyList())
    val meshHealth = MutableStateFlow(MeshHealth())
    /** Direct neighbor endpoint → origin, once learned from the neighbor's own packets. */
    val neighborOrigins = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Stored packets no neighbor has received yet. */
    val waiting = MutableStateFlow(0)
    /** Packets crossing this phone's links, live, for the Live Mesh animation. Not stored. */
    val linkActivity = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** The running simulation, when simulation mode is on (for its on-screen controls). */
    val simulation = MutableStateFlow<MeshSimulation?>(null)
    /** My latest GPS fix while the mesh service runs (Phase 10 live position). */
    val myFix = MutableStateFlow<com.lifeline.mesh.ai.GeoFix?>(null)
    /** Other phones' latest positions by origin, current session only (cleared when the mesh stops or the mode changes). */
    val positions = MutableStateFlow<Map<Int, com.lifeline.mesh.mesh.PeerPosition>>(emptyMap())
    /**
     * A running fall alarm (fall detected, or "Try the fall alarm"): the service counts down, rings and then sends a
     * panic SOS; the app shows the countdown with "I'm OK". Null = no alarm.
     */
    val fallAlarm = MutableStateFlow<com.lifeline.mesh.safety.FallAlarm?>(null)

    /** [vitals] = the watch reading that started it (watch vitals alarm); null = a fall. */
    fun startFallAlarm(test: Boolean, vitals: String? = null) {
        fallAlarm.compareAndSet(null, com.lifeline.mesh.safety.FallAlarm(System.currentTimeMillis(), test, vitals))
    }

    fun cancelFallAlarm() { fallAlarm.value = null }

    /** Skip the rest of the countdown and send now. */
    fun sendFallSosNow() {
        fallAlarm.value?.let { a -> fallAlarm.value = a.copy(startedAt = System.currentTimeMillis() - com.lifeline.mesh.safety.FallAlarm.COUNTDOWN_MS) }
    }

    /**
     * Jobs responders have reached: (responder origin, alert id). Checked whenever positions or packets change, so a team
     * that arrived and moved on to its next job is shown heading to that one. Current mesh session only, like positions.
     */
    val jobsDone = MutableStateFlow<Set<Pair<Int, Long>>>(emptySet())

    init {
        scope.launch {
            kotlinx.coroutines.flow.combine(positions, packets) { pos, pk -> pos to pk }.collect { (pos, pk) ->
                if (pos.isEmpty()) return@collect
                val hs = com.lifeline.mesh.ui.rescuer.headingsFrom(pk, System.currentTimeMillis(), jobsDone.value)
                val now = com.lifeline.mesh.triage.Responders.arrivals(hs, pos.mapValues { (_, p) -> p.lat to p.lon })
                if (!jobsDone.value.containsAll(now)) jobsDone.value = jobsDone.value + now
            }
        }
    }

    /** The user pressed Stop; don't auto-start the mesh again until they press Start or reopen the app. */
    @Volatile var userStoppedMesh = false

    @Volatile private var router: Router? = null

    fun attachRouter(r: Router, sim: MeshSimulation?) {
        router = r
        simulation.value = sim
    }

    fun detachRouter(r: Router?) {
        if (router === r) {
            router = null
            simulation.value = null
        }
    }

    /**
     * Sends a packet this phone created. [build] gets a fresh header (TTL 5, hops 0).
     * If the mesh service is not running, the packet is saved in Room as mine and the service sends it
     * when it next starts (it restores unsent packets from Room), so nothing is lost.
     */
    fun originate(ttl: Int = PacketCodec.TTL_DEFAULT, build: (Header) -> Packet): Packet {
        val r = router
        if (r != null) {
            val p = build(r.newHeader(ttl))
            r.originate(p)
            return p
        }
        val s = settings.value ?: error("settings not loaded")
        var id = 0L
        while (id == 0L) id = SecureRandom().nextLong()
        val p = build(Header(id, s.originId, ttl, 0, System.currentTimeMillis() / 1000))
        val raw = PacketCodec.encode(p)
        scope.launch {
            val now = System.currentTimeMillis()
            val sim = s.simulationMode
            database.packets().insert(p.toEntity(raw, null, 0, mine = true, receivedAt = now, simulated = sim))
            if (p is Packet.Ack) database.packets().markAcked(p.refId, p.etaMinutes)
            database.events().insert(EventEntity(time = now, kind = MeshEvent.Kind.QUEUED.name,
                text = "Your ${Router.label(p)} is saved. The mesh is not running yet.", packetId = id, simulated = sim))
        }
        return p
    }

    /**
     * "Help is coming" for a merged alert: one Ack per report (at most 5, well inside the 10-per-minute rate limit),
     * so every phone that reported it gets the reply.
     */
    fun sendAcks(reportIds: List<Long>, etaMinutes: Int, note: String) {
        reportIds.take(5).forEach { id -> originate { h -> Packet.Ack(h, id, etaMinutes.coerceIn(0, 255), note.trim()) } }
    }

    /**
     * Sample alerts, check-ins and reports around [lat]/[lon], for rehearsing the Rescue screen. Stored as simulated rows,
     * so they never appear next to real alerts; refused outside simulation mode.
     */
    fun loadSampleAlerts(lat: Double, lon: Double): Boolean {
        val s = settings.value ?: return false
        if (!s.simulationMode) return false
        scope.launch {
            val now = System.currentTimeMillis()
            val m = 1.0 / 111_320
            fun at(northM: Double, eastM: Double) = Pair(Geo.toE6(lat + northM * m), Geo.toE6(lon + eastM * m / kotlin.math.cos(Math.toRadians(lat))))
            fun h(origin: Int, minutesAgo: Int, hops: Int) =
                Header(SecureRandom().nextLong(), origin, PacketCodec.TTL_DEFAULT - hops + 1, hops - 1, (now / 1000) - minutesAgo * 60)
            val T = Need.TRAPPED.bit; val M = Need.MEDICAL.bit; val W = Need.WATER_FOOD.bit; val F = Need.FIRE.bit
            data class Sample(val p: Packet, val minutesAgo: Int, val hops: Int)
            val samples = listOf(
                at(300.0, 20.0).let { (a, b) -> Sample(Packet.Alert(h(0x51A11001, 3, 2), a, b, 2, T or M, 3, 5, "Water rising, 5 on roof, elderly man ill"), 3, 2) },
                at(330.0, 40.0).let { (a, b) -> Sample(Packet.Alert(h(0x51A11002, 2, 3), a, b, 3, T or M, 3, 4, "Paani badh gaya, chhat par 4 log"), 2, 3) },
                at(-60.0, 800.0).let { (a, b) -> Sample(Packet.Alert(h(0x51A11003, 6, 2), a, b, 2, F, 3, 3, "आग लगी है, ३ लोग अंदर हैं"), 6, 2) },
                at(-350.0, -350.0).let { (a, b) -> Sample(Packet.Alert(h(0x51A11004, 12, 1), a, b, 4, W, 1, 8, "Need drinking water for 8 people"), 12, 1) },
                Sample(Packet.Alert(h(0x51A11005, 20, 3), 0, 0, Geo.ACCURACY_UNKNOWN, M, 2, 1, "Pregnant woman needs a doctor"), 20, 3),
                at(110.0, -110.0).let { (a, b) -> Sample(Packet.Alert(h(0x51A11006, 1, 1), a, b, 1, Need.PANIC.bit, 3, 1, ""), 1, 1) },
                at(380.0, 120.0).let { (a, b) -> Sample(Packet.CheckIn(h(0x51A11007, 2, 1), a, b, 1, "Asha"), 2, 1) },
                at(-700.0, 500.0).let { (a, b) -> Sample(Packet.CheckIn(h(0x51A11008, 10, 2), a, b, 2, "Ravi"), 10, 2) },
                at(600.0, 0.0).let { (a, b) -> Sample(Packet.Report(h(0x51A11009, 8, 2), a, b, 2, ReportType.ROAD_BLOCKED, "Main road flooded"), 8, 2) },
                at(-1200.0, 300.0).let { (a, b) -> Sample(Packet.Report(h(0x51A1100A, 15, 3), a, b, 3, ReportType.SHELTER_OPEN, "School hall, 200 beds"), 15, 3) },
            )
            for ((p, ago, hops) in samples) {
                database.packets().insert(p.toEntity(PacketCodec.encode(p), "sample", hops, mine = false, receivedAt = now - ago * 60_000L, simulated = true))
            }
            database.events().insert(EventEntity(time = now, kind = MeshEvent.Kind.RECEIVED.name,
                text = "Loaded ${samples.size} sample packets (simulated).", packetId = null, simulated = true))
        }
        return true
    }

    /** Alert ids the server assigned to my team (Phase 10); the service raises an alarm when they arrive. */
    val assignedToMe: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * A packet fetched from the sync server (Phase 10 internet relay). With the mesh running it goes through the router
     * like a packet from a neighbor (deduplicated, stored, relayed into the local mesh, replies recognised); otherwise
     * it is stored directly, marked "internet". Never in simulation mode. True if it was new.
     */
    fun receiveFromInternet(raw: ByteArray): Boolean {
        if (settings.value?.simulationMode != false) return false
        val p = (PacketCodec.decode(raw) as? com.lifeline.mesh.packet.DecodeResult.Ok)?.packet ?: return false
        if (!Router.storable(p)) return false
        val r = router
        if (r != null) { r.onReceived(Router.INTERNET, raw); return true }
        val now = System.currentTimeMillis()
        val mine = p.header.origin == settings.value?.originId
        return kotlinx.coroutines.runBlocking {
            val added = database.packets().insert(p.toEntity(raw, Router.INTERNET, p.header.hops + 1, mine = mine, receivedAt = now)) != -1L
            if (added && p is Packet.Ack) database.packets().markAcked(p.refId, p.etaMinutes)
            added
        }
    }

    /** Ping for one of my packets; needs the mesh running (a queued ping would measure nothing). */
    fun sendPing(refId: Long): Boolean {
        val r = router ?: return false
        r.sendPing(refId)
        return true
    }
}
