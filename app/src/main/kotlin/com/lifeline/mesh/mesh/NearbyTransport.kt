package com.lifeline.mesh.mesh

import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.lifeline.mesh.packet.PacketCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Per-neighbor link state for the UI (CLAUDE.md 7.1). */
enum class LinkState { DISCOVERED, CONNECTING, CONNECTED, LOST }

data class Neighbor(
    val endpointId: String,
    /** Endpoint name, e.g. "Asha-3fa1". */
    val name: String,
    val state: LinkState,
    /** Wall-clock ms of the last packet or state change from this neighbor. */
    val lastHeard: Long,
)

/**
 * Google Nearby Connections, P2P_CLUSTER, advertising and discovering at the same time (CLAUDE.md 7.1).
 *
 * - Endpoint name = display name + last 4 hex of the origin, e.g. "Asha-3fa1".
 * - Connections are auto-accepted when the other side's name has our "name-hhhh" shape (discovery is
 *   already filtered by our service ID). Links are not authenticated: alerts are meant for everyone nearby.
 * - Only one side asks to connect, to avoid both phones requesting at once: the one whose 4-hex suffix
 *   sorts lower asks at once, the other only if nothing happened after [FOLLOWER_WAIT_MS].
 * - A dropped link is retried with backoff 2, 4, 8, 16, 30, 30… s (plus jitter) while the phone is
 *   still discovered, or for [RETRY_AFTER_LOST_MS] after it was lost.
 * - Payloads are BYTES only, never over 1 KB.
 */
class NearbyTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val endpointName: String,
    private val onRadioError: (String?) -> Unit,
) : Transport {

    companion object {
        const val SERVICE_ID = "com.lifeline.mesh.v1"
        private const val TAG = "NearbyTransport"
        private val NAME_SHAPE = Regex(".*-[0-9a-f]{4}")
        private const val FOLLOWER_WAIT_MS = 6_000L
        private const val RETRY_AFTER_LOST_MS = 120_000L
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private val BACKOFF_S = longArrayOf(2, 4, 8, 16, 30)
    }

    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val strategy = Strategy.P2P_CLUSTER
    private val myShort = endpointName.takeLast(4)

    @Volatile private var listener: Transport.Listener? = null
    @Volatile private var running = false

    private val _neighbors = MutableStateFlow<Map<String, Neighbor>>(emptyMap())
    val neighbors: StateFlow<Map<String, Neighbor>> = _neighbors

    private val pendingSends = ConcurrentHashMap<Long, (Boolean) -> Unit>()
    private val attempts = ConcurrentHashMap<String, Int>()
    private val retryJobs = ConcurrentHashMap<String, Job>()
    private val lostAt = ConcurrentHashMap<String, Long>()

    override fun setListener(listener: Transport.Listener?) {
        this.listener = listener
    }

    // ------------------------------------------------------------------ start / stop

    fun start() {
        if (running) return
        running = true
        onRadioError(null)
        val adv = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(endpointName, SERVICE_ID, lifecycle, adv)
            .addOnSuccessListener { Log.i(TAG, "advertising as $endpointName") }
            .addOnFailureListener { e -> radioFailure("advertise", e) }
        val disc = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(SERVICE_ID, discovery, disc)
            .addOnSuccessListener { Log.i(TAG, "discovering") }
            .addOnFailureListener { e -> radioFailure("discover", e) }
    }

    fun stop() {
        if (!running) return
        running = false
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        val wasConnected = _neighbors.value.values.filter { it.state == LinkState.CONNECTED }.map { it.endpointId }
        _neighbors.value = emptyMap()
        pendingSends.values.forEach { it(false) }
        pendingSends.clear()
        wasConnected.forEach { listener?.onDisconnected(it) }
    }

    private fun radioFailure(what: String, e: Exception) {
        val code = (e as? ApiException)?.statusCode
        if (code == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING || code == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) return
        Log.w(TAG, "$what failed: ${statusName(code)} ${e.message}")
        onRadioError(
            when (code) {
                ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "Bluetooth error. Turn Bluetooth off and on."
                ConnectionsStatusCodes.STATUS_RADIO_ERROR -> "The radio failed to start. Turn Bluetooth and Wi-Fi off and on."
                ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
                ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADVERTISE,
                ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT,
                ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES,
                ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
                ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION -> "Nearby devices permission is missing."
                else -> "Could not $what nearby phones (${statusName(code)})."
            }
        )
    }

    // ------------------------------------------------------------------ Transport

    override fun send(peer: String, bytes: ByteArray, onResult: (Boolean) -> Unit) {
        if (bytes.size > PacketCodec.MAX_PAYLOAD || _neighbors.value[peer]?.state != LinkState.CONNECTED) {
            onResult(false)
            return
        }
        val payload = Payload.fromBytes(bytes)
        pendingSends[payload.id] = onResult
        client.sendPayload(peer, payload).addOnFailureListener { e ->
            Log.w(TAG, "send to $peer failed: ${e.message}")
            pendingSends.remove(payload.id)?.invoke(false)
        }
    }

    // ------------------------------------------------------------------ discovery

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            Log.i(TAG, "found $endpointId ${info.endpointName}")
            lostAt.remove(endpointId)
            val current = _neighbors.value[endpointId]?.state
            if (current == LinkState.CONNECTED || current == LinkState.CONNECTING) return
            setState(endpointId, info.endpointName, LinkState.DISCOVERED)
            attempts.remove(endpointId)
            scheduleConnect(endpointId, if (leads(info.endpointName)) 0L else FOLLOWER_WAIT_MS)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.i(TAG, "lost $endpointId")
            lostAt[endpointId] = System.currentTimeMillis()
            val n = _neighbors.value[endpointId] ?: return
            if (n.state != LinkState.CONNECTED) setState(endpointId, n.name, LinkState.LOST)
        }
    }

    /** True if this phone should be the one to ask (lower 4-hex suffix asks first). */
    private fun leads(otherName: String): Boolean = myShort < otherName.takeLast(4)

    private fun scheduleConnect(endpointId: String, delayMs: Long) {
        retryJobs.remove(endpointId)?.cancel()
        retryJobs[endpointId] = scope.launch {
            delay(delayMs)
            retryJobs.remove(endpointId)
            requestConnection(endpointId)
        }
    }

    private fun requestConnection(endpointId: String) {
        if (!running) return
        val n = _neighbors.value[endpointId] ?: return
        if (n.state == LinkState.CONNECTED || n.state == LinkState.CONNECTING) return
        val lost = lostAt[endpointId]
        if (lost != null && System.currentTimeMillis() - lost > RETRY_AFTER_LOST_MS) return
        setState(endpointId, n.name, LinkState.CONNECTING)
        client.requestConnection(endpointName, endpointId, lifecycle)
            .addOnFailureListener { e ->
                val code = (e as? ApiException)?.statusCode
                if (code == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) return@addOnFailureListener
                Log.w(TAG, "request $endpointId failed: ${statusName(code)}")
                connectFailed(endpointId)
            }
        // Watchdog: a request that never resolves must not leave the link stuck in "connecting".
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_neighbors.value[endpointId]?.state == LinkState.CONNECTING) {
                Log.w(TAG, "connect $endpointId timed out")
                connectFailed(endpointId)
            }
        }
    }

    private fun connectFailed(endpointId: String) {
        val n = _neighbors.value[endpointId] ?: return
        if (n.state == LinkState.CONNECTED) return
        setState(endpointId, n.name, if (lostAt.containsKey(endpointId)) LinkState.LOST else LinkState.DISCOVERED)
        retryLater(endpointId)
    }

    /** Backoff 2, 4, 8, 16, 30, 30… s; the follower waits an extra few seconds to avoid a collision. */
    private fun retryLater(endpointId: String) {
        if (!running) return
        val n = attempts.merge(endpointId, 1, Int::plus) ?: 1
        val base = BACKOFF_S[minOf(n - 1, BACKOFF_S.lastIndex)] * 1000
        val name = _neighbors.value[endpointId]?.name ?: ""
        val extra = if (leads(name)) 0L else 1_500L
        scheduleConnect(endpointId, base + extra + Random.nextLong(0, 500))
    }

    // ------------------------------------------------------------------ connections

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "initiated $endpointId ${info.endpointName} incoming=${info.isIncomingConnection}")
            if (!NAME_SHAPE.matches(info.endpointName)) {
                client.rejectConnection(endpointId)
                return
            }
            retryJobs.remove(endpointId)?.cancel() // the other phone asked first; no need for ours
            setState(endpointId, info.endpointName, LinkState.CONNECTING)
            client.acceptConnection(endpointId, payloads)
                .addOnFailureListener { e -> Log.w(TAG, "accept $endpointId failed: ${e.message}") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val n = _neighbors.value[endpointId]
            val name = n?.name ?: endpointId
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                Log.i(TAG, "connected $endpointId $name")
                attempts.remove(endpointId)
                lostAt.remove(endpointId)
                retryJobs.remove(endpointId)?.cancel()
                setState(endpointId, name, LinkState.CONNECTED)
                listener?.onConnected(endpointId)
            } else {
                Log.w(TAG, "connection to $endpointId failed: ${statusName(result.status.statusCode)}")
                connectFailed(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "disconnected $endpointId")
            val name = _neighbors.value[endpointId]?.name ?: endpointId
            setState(endpointId, name, LinkState.LOST)
            lostAt[endpointId] = System.currentTimeMillis()
            listener?.onDisconnected(endpointId)
            // The other phone may still be in range (e.g. it restarted its radio): try again with backoff.
            attempts.remove(endpointId)
            retryLater(endpointId)
        }
    }

    // ------------------------------------------------------------------ payloads

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            touch(endpointId)
            listener?.onReceived(endpointId, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> pendingSends.remove(update.payloadId)?.invoke(true)
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> pendingSends.remove(update.payloadId)?.invoke(false)
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------------------ state

    private fun setState(endpointId: String, name: String, state: LinkState) {
        _neighbors.update { it + (endpointId to Neighbor(endpointId, name, state, System.currentTimeMillis())) }
    }

    private fun touch(endpointId: String) {
        _neighbors.update { map ->
            val n = map[endpointId] ?: return@update map
            map + (endpointId to n.copy(lastHeard = System.currentTimeMillis()))
        }
    }

    fun nameOf(endpointId: String): String = _neighbors.value[endpointId]?.name ?: endpointId

    private fun statusName(code: Int?): String =
        if (code == null) "unknown" else ConnectionsStatusCodes.getStatusCodeString(code)
}
