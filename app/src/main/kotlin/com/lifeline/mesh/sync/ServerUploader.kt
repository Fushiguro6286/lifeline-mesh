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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Uploads queued alerts, check-ins, acks and reports to the sync server (CLAUDE.md 7.6). The only network user in
 * the app. Uploads by itself when Android reports validated internet; "Upload now" in Settings tries on any network
 * (a laptop hotspot with no internet is not "validated"). Simulated packets are never uploaded. Nothing is broadcast
 * to the mesh about it.
 */
class ServerUploader(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: StateFlow<Settings?>,
    private val store: SettingsStore,
) {
    data class State(
        val internet: Boolean = false,
        val pending: Int = 0,
        val uploading: Boolean = false,
        val lastError: String? = null,
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
                val body = SyncFormat.json.encodeToString(batch.map { SyncFormat.toUpload(it, s.originId) })
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
)

/** Pure mapping and URL rules (unit-tested). */
object SyncFormat {
    val json = Json { encodeDefaults = false; explicitNulls = false }

    fun hex64(v: Long) = "%016x".format(v)
    fun hex32(v: Int) = "%08x".format(v)

    fun toUpload(p: PacketEntity, myOrigin: Int): UploadPacket {
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
