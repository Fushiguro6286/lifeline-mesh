package com.lifeline.mesh.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.lifeline.mesh.data.AppDatabase
import com.lifeline.mesh.data.PacketEntity
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.data.SettingsStore
import com.lifeline.mesh.packet.Geo
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.PacketType
import com.lifeline.mesh.packet.ReportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Uploads queued alerts, check-ins, acks and reports to the sync server (CLAUDE.md 7.6), and runs the internet relay
 * (Phase 10): polls the server for alerts near this team and replies to my alerts, and hands them to the mesh.
 * The only network user in the app. Uploads by itself when Android reports validated internet; "Upload now" in Settings tries on any network
 * (a laptop hotspot with no internet is not "validated"). Simulated packets are never uploaded. Nothing is broadcast
 * to the mesh about it.
 */
class ServerUploader(
    private val context: Context,
    private val db: AppDatabase,
    private val repo: com.lifeline.mesh.AppRepository,
    private val store: SettingsStore,
) {
    private val settings: StateFlow<Settings?> get() = repo.settings
    data class State(
        val internet: Boolean = false,
        val pending: Int = 0,
        val uploading: Boolean = false,
        val lastError: String? = null,
        /** Internet relay (Phase 10): last successful poll (ms) and packets received from the server so far. */
        val lastPollAt: Long? = null,
        val received: Int = 0,
        val pollError: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** true = the user pressed Upload now (no wait, any network). */
    private val kick = Channel<Boolean>(Channel.CONFLATED)
    @Volatile private var wifi: Network? = null
    private var retryJob: Job? = null
    private var backoffMs = FIRST_RETRY_MS
    private var started = false

    fun start() {
        if (started) return
        started = true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val was = _state.value.internet
                _state.update { it.copy(internet = ok) }
                if (ok && !was && _state.value.pending > 0) kick.trySend(false)
            }
            override fun onLost(network: Network) { _state.update { it.copy(internet = false) } }
        })
        // A LAN server (the laptop) is reached over Wi-Fi even when the default network is mobile data.
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { wifi = network }
                override fun onLost(network: Network) { if (wifi == network) wifi = null }
            },
        )
        scope.launch {
            db.packets().observePendingUpload().collect { n ->
                _state.update { it.copy(pending = n) }
                if (n > 0 && _state.value.internet) kick.trySend(false)
            }
        }
        // Internet relay (Phase 10): poll the server for alerts near me (teams) and replies to my alerts.
        // Never in simulation mode, so real and simulated packets never mix.
        scope.launch {
            var since = 0L
            while (true) {
                delay(POLL_MS)
                val s = settings.value ?: continue
                if (s.simulationMode || (!_state.value.internet && wifi == null)) continue
                since = poll(s, since) ?: since
            }
        }
        scope.launch {
            for (manual in kick) {
                // Auto: let a burst of arriving packets go in one request. Manual: let a just-saved URL land first.
                delay(if (manual) 300 else 2_000)
                if (!manual && !_state.value.internet) continue
                val ok = uploadAll()
                if (ok) { retryJob?.cancel(); backoffMs = FIRST_RETRY_MS }
                else if (_state.value.internet) scheduleRetry()
            }
        }
    }

    fun uploadNow() { kick.trySend(true) }

    private fun scheduleRetry() {
        retryJob?.cancel()
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_MS)
        retryJob = scope.launch { delay(wait); kick.trySend(false) }
    }

    /** Sends everything pending in batches. False on the first failure (the rest stays queued). */
    private suspend fun uploadAll(): Boolean {
        val s = settings.value ?: return false
        _state.update { it.copy(uploading = true) }
        var sent = 0
        try {
            while (true) {
                val batch = db.packets().pendingUpload(BATCH)
                if (batch.isEmpty()) break
                val body = SyncFormat.json.encodeToString(batch.map { SyncFormat.toUpload(it, s.originId, s.phone) })
                val err = withContext(Dispatchers.IO) { post(s.serverUrl, body) }
                if (err != null) {
                    _state.update { it.copy(lastError = err) }
                    return false
                }
                db.packets().markUploaded(batch.map { it.id })
                sent += batch.size
            }
            if (sent > 0) store.setLastUpload(System.currentTimeMillis(), sent)
            _state.update { it.copy(lastError = null) }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "upload failed", e)
            _state.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
            return false
        } finally {
            _state.update { it.copy(uploading = false) }
        }
    }

    /** One poll; returns the server's cursor for next time, or null on failure. Received packets enter the mesh. */
    private suspend fun poll(s: Settings, since: Long): Long? = withContext(Dispatchers.IO) {
        val base = SyncFormat.normalizeUrl(s.serverUrl) ?: return@withContext null
        val fix = repo.myFix.value
        val url = try {
            URL("$base/api/feed?" + SyncFormat.feedQuery(s.originId, s.role.code, s.displayName,
                fix?.lat.takeIf { s.role.isTeam }, fix?.lon.takeIf { s.role.isTeam }, since))
        } catch (e: MalformedURLException) { return@withContext null }
        val net = if (SyncFormat.isLocalHost(url.host)) wifi else null
        val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            if (conn.responseCode !in 200..299) {
                _state.update { it.copy(pollError = "The server answered ${conn.responseCode}.") }
                return@withContext null
            }
            val feed = SyncFormat.json.decodeFromString<FeedResponse>(conn.inputStream.bufferedReader().use { it.readText() })
            var injected = 0
            for (p in feed.packets) {
                val bytes = runCatching { java.util.Base64.getDecoder().decode(p.raw) }.getOrNull() ?: continue
                if (p.assigned) p.id.toULongOrNull(16)?.toLong()?.let { repo.assignedToMe.add(it) }
                if (repo.receiveFromInternet(bytes)) injected++
            }
            // Official warnings: into the mesh like any packet (duplicates are dropped by message ID).
            for (w in feed.warnings) {
                val bytes = runCatching { com.lifeline.mesh.packet.PacketCodec.encode(SyncFormat.warningPacket(w)) }.getOrNull() ?: continue
                if (repo.receiveFromInternet(bytes)) injected++
            }
            _state.update { it.copy(lastPollAt = System.currentTimeMillis(), received = it.received + injected, pollError = null) }
            feed.now
        } catch (e: Exception) {
            _state.update { it.copy(pollError = "Cannot reach the server for the internet relay.") }
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Null on success, otherwise a plain sentence for Settings. */
    private fun post(serverUrl: String, body: String): String? {
        val base = SyncFormat.normalizeUrl(serverUrl) ?: return "The server address is not valid."
        val url = try { URL("$base/api/packets") } catch (e: MalformedURLException) { return "The server address is not valid." }
        val net = if (SyncFormat.isLocalHost(url.host)) wifi else null
        val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        return try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) { conn.inputStream.use { it.readBytes() }; null }
            else "The server answered $code."
        } catch (e: UnknownHostException) {
            "Server address not found: ${url.host}."
        } catch (e: ConnectException) {
            "Cannot reach the server at $base. Is it running, and is this phone on the same Wi-Fi?"
        } catch (e: SocketTimeoutException) {
            "The server at $base did not answer in time."
        } catch (e: SecurityException) {
            "Android blocked the connection. Allow \"Nearby devices\" for Lifeline Mesh."
        } catch (e: java.io.IOException) {
            "Upload failed: ${e.message ?: e.javaClass.simpleName}."
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "ServerUploader"
        private const val BATCH = 200
        private const val FIRST_RETRY_MS = 30_000L
        private const val MAX_RETRY_MS = 5 * 60_000L
        private const val POLL_MS = 15_000L
    }
}

/** What the server stores per packet (server/main.py PacketIn). IDs as hex so 64-bit values survive JSON. */
@Serializable
data class UploadPacket(
    val id: String,
    val type: String,
    val origin: String,
    val hops: Int,
    val sentAt: Long,
    val receivedAt: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Int? = null,
    val needs: List<String> = emptyList(),
    val severity: Int? = null,
    val people: Int? = null,
    val note: String? = null,
    val refId: String? = null,
    val etaMinutes: Int? = null,
    val reportType: String? = null,
    val name: String? = null,
    val uploadedBy: String,
    /** The packet as sent (base64), for teams that inject it into their own mesh (Phase 10). */
    val raw: String? = null,
    /** My phone number, only on my own packets (Settings; never sent over the mesh). */
    val contactPhone: String? = null,
)

/** One entry of GET /api/feed. */
@Serializable
data class FeedPacket(val id: String, val raw: String, val assigned: Boolean = false)

/** An official warning in force (NDMA SACHET, or a drill from the server laptop). Times in unix seconds. */
@Serializable
data class FeedWarning(
    val id: String, val event: String = "", val severity: String = "", val headline: String = "", val area: String = "",
    val sent: Long = 0, val expires: Long = 0, val drill: Boolean = false,
)

@Serializable
data class FeedResponse(val now: Long, val packets: List<FeedPacket> = emptyList(), val warnings: List<FeedWarning> = emptyList())

/** Pure mapping and URL rules (unit-tested). */
object SyncFormat {
    val json = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }

    /** Query for GET /api/feed: who I am, my team role and where I am (teams), and what I already have. */
    fun feedQuery(origin: Int, role: Int, name: String, lat: Double?, lon: Double?, since: Long): String = buildList {
        add("origin=" + hex32(origin)); add("role=$role"); add("since=$since")
        if (name.isNotBlank()) add("name=" + java.net.URLEncoder.encode(name, "UTF-8"))
        if (lat != null && lon != null) { add("lat=$lat"); add("lon=$lon") }
    }.joinToString("&")

    /**
     * The mesh packet for an official warning. Same CAP identifier → same message ID on every phone, so copies brought
     * in by several online phones are dropped as duplicates. TTL 5 like an alert.
     */
    fun warningPacket(w: FeedWarning): com.lifeline.mesh.packet.Packet.Warning {
        val clamp = { t: Long -> t.coerceIn(0, 0xFFFF_FFFFL) }
        val header = com.lifeline.mesh.packet.Header(com.lifeline.mesh.packet.Warnings.messageId(w.id),
            com.lifeline.mesh.packet.Warnings.ORIGIN, com.lifeline.mesh.packet.PacketCodec.TTL_DEFAULT, 0, clamp(w.sent))
        return com.lifeline.mesh.packet.Packet.Warning(header, com.lifeline.mesh.packet.Warnings.level(w.severity), w.drill,
            clamp(w.expires), w.event, w.headline)
    }

    fun hex64(v: Long) = "%016x".format(v)
    fun hex32(v: Int) = "%08x".format(v)

    fun toUpload(p: PacketEntity, myOrigin: Int, myPhone: String = ""): UploadPacket {
        val type = when (p.type) {
            PacketType.ALERT.code -> "alert"
            PacketType.CHECK_IN.code -> "check_in"
            PacketType.ACK.code -> "ack"
            PacketType.REPORT.code -> "report"
            else -> error("type ${p.type} is not uploaded")
        }
        // An alert with no fix is sent as 0,0 with unknown accuracy: that is "no position", not the Gulf of Guinea.
        val noFix = p.accuracy == Geo.ACCURACY_UNKNOWN && p.lat == 0.0 && p.lon == 0.0
        return UploadPacket(
            id = hex64(p.id),
            type = type,
            origin = hex32(p.origin),
            hops = p.hops,
            sentAt = p.sentAt,
            receivedAt = p.receivedAt,
            lat = p.lat.takeUnless { noFix },
            lon = p.lon.takeUnless { noFix },
            accuracyM = p.accuracy?.let { Geo.accuracyMeters(it) },
            needs = p.needs?.let { m -> Need.entries.filter { m and it.bit != 0 }.map { it.label } } ?: emptyList(),
            severity = p.severity,
            people = p.people,
            note = p.note?.takeIf { it.isNotEmpty() },
            refId = p.refId?.let(::hex64),
            etaMinutes = p.eta,
            reportType = p.reportType?.let { c -> ReportType.entries.firstOrNull { it.code == c }?.name?.lowercase() },
            name = p.name,
            uploadedBy = hex32(myOrigin),
            raw = java.util.Base64.getEncoder().encodeToString(p.rawBytes).takeIf { p.rawBytes.isNotEmpty() },
            contactPhone = myPhone.takeIf { p.isMine && it.isNotBlank() },
        )
    }

    /** "192.168.1.5:8000" → "http://192.168.1.5:8000"; trailing slashes dropped; null if blank or not http(s). */
    fun normalizeUrl(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty()) return null
        val withScheme = if ("://" in t) t else "http://$t"
        return withScheme.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** Private-network hosts (the laptop on the venue Wi-Fi or a hotspot), reached over Wi-Fi. Not loopback. */
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase()
        if (h.endsWith(".local")) return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
    }
}
