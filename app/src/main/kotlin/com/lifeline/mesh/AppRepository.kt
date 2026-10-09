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
import com.lifeline.mesh.mesh.MeshEvent
import com.lifeline.mesh.mesh.MeshHealth
import com.lifeline.mesh.mesh.Neighbor
import com.lifeline.mesh.mesh.Router
import com.lifeline.mesh.packet.Header
import com.lifeline.mesh.packet.Packet
import com.lifeline.mesh.packet.PacketCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun setOnboardingDone() = scope.launch { settingsStore.setOnboardingDone(true) }

    // ------------------------------------------------------------------ Room

    val packets: StateFlow<List<PacketEntity>> = database.packets().observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val events: StateFlow<List<EventEntity>> = database.events().observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val topology: StateFlow<List<TopologyEntity>> = database.topology().observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val metrics: StateFlow<List<MetricEntity>> = database.metrics().observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    // ------------------------------------------------------------------ live mesh state (written by MeshService)

    val meshRunning = MutableStateFlow(false)
    val neighbors = MutableStateFlow<List<Neighbor>>(emptyList())
    val meshHealth = MutableStateFlow(MeshHealth())
    /** The user pressed Stop; don't auto-start the mesh again until they press Start or reopen the app. */
    @Volatile var userStoppedMesh = false

    @Volatile private var router: Router? = null

    fun attachRouter(r: Router) { router = r }
    fun detachRouter(r: Router?) { if (router === r) router = null }

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
            database.packets().insert(p.toEntity(raw, null, 0, mine = true, receivedAt = now))
            database.events().insert(EventEntity(time = now, kind = MeshEvent.Kind.QUEUED.name,
                text = "Your ${Router.label(p)} is saved. The mesh is not running yet.", packetId = id))
        }
        return p
    }

    /** Ping for one of my packets; needs the mesh running (a queued ping would measure nothing). */
    fun sendPing(refId: Long): Boolean {
        val r = router ?: return false
        r.sendPing(refId)
        return true
    }
}
