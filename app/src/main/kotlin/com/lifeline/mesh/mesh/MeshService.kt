package com.lifeline.mesh.mesh

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lifeline.mesh.AppRepository
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.MainActivity
import com.lifeline.mesh.R
import com.lifeline.mesh.data.EventEntity
import com.lifeline.mesh.data.MetricEntity
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.data.TopologyEntity
import com.lifeline.mesh.data.toEntity
import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.Packet
import com.lifeline.mesh.ui.components.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service (type connectedDevice) that owns [NearbyTransport], [Router] and [HelloBeacon], so
 * relaying continues with the screen off or the app in the background (CLAUDE.md 4 and 11).
 * Holds a partial wake lock while running: without it the CPU sleeps with the screen off and the
 * 50–300 ms forwarding delays and 10 s Hellos stall.
 */
class MeshService : Service() {

    companion object {
        private const val TAG = "MeshService"
        private const val CHANNEL_MESH = "mesh"
        private const val CHANNEL_REPLIES = "replies"
        private const val NOTIF_MESH = 1
        private const val NOTIF_REPLY = 2
        private const val ACTION_STOP = "com.lifeline.mesh.STOP_MESH"
        private const val KEEP_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MeshService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Room writes in arrival order (a packet's row exists before its "handed to" update). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dbWrites = Dispatchers.IO.limitedParallelism(1)

    private lateinit var repo: AppRepository
    private var transport: NearbyTransport? = null
    private var router: Router? = null
    private var beacon: HelloBeacon? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var restartJob: Job? = null
    @Volatile private var radioError: String? = null
    private var receiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = (application as LifelineApp).repository
        createChannels()
        if (!goForeground(0)) {
            stopSelf()
            return
        }
        acquireWakeLock()
        registerRadioReceiver()
        scope.launch { startMesh() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            repo.userStoppedMesh = true
            stopSelf()
            return START_NOT_STICKY
        }
        repo.userStoppedMesh = false
        return START_STICKY
    }

    override fun onDestroy() {
        beacon?.stop()
        transport?.stop()
        repo.detachRouter(router)
        repo.meshRunning.value = false
        repo.neighbors.value = emptyList()
        if (receiverRegistered) runCatching { unregisterReceiver(radioReceiver) }
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ mesh

    private suspend fun startMesh() {
        val settings = repo.settings.filterNotNull().first()
        refreshHealth()
        if (!repo.meshHealth.value.permissionsOk) {
            Log.w(TAG, "missing permissions ${repo.meshHealth.value.missingPermissions}; not starting")
            stopSelf()
            return
        }
        val t = NearbyTransport(this, scope, endpointName(settings)) { err ->
            radioError = err
            refreshHealth()
        }
        val r = Router(
            me = settings.originId,
            transport = t,
            scope = scope,
            listener = Persist(),
            clock = SystemClock::elapsedRealtime,
            wallSeconds = { System.currentTimeMillis() / 1000 },
            peerLabel = t::nameOf,
        )
        applySettings(r, settings)
        r.battery = batteryPercent()

        val now = System.currentTimeMillis()
        val stored = withContext(Dispatchers.IO) { repo.database.packets().since(now - KEEP_MS) }
        r.restore(stored.map { StoredPacket(it.rawBytes, it.isMine, ageMs = now - it.receivedAt) })

        transport = t
        router = r
        repo.attachRouter(r)
        t.start()
        beacon = HelloBeacon(r).also { it.start(scope) }
        repo.meshRunning.value = true
        Log.i(TAG, "mesh started as ${endpointName(settings)}, restored ${stored.size} packets")

        scope.launch {
            t.neighbors.collect { map ->
                val list = map.values.sortedBy { it.name }
                repo.neighbors.value = list
                updateNotification(list.count { it.state == LinkState.CONNECTED })
            }
        }
        scope.launch {
            repo.settings.filterNotNull().map { it.displayName to it.role }.distinctUntilChanged().collect {
                repo.settings.value?.let { s -> applySettings(r, s) }
            }
        }
        scope.launch {
            while (isActive) {
                delay(60_000)
                r.battery = batteryPercent()
            }
        }
    }

    private fun applySettings(r: Router, s: Settings) {
        r.displayName = s.displayName
        r.role = HelloRole.fromCode(s.role.code) ?: HelloRole.NEEDS_HELP
    }

    /** "Asha-3fa1"; a phone without a name is "Phone-3fa1". */
    private fun endpointName(s: Settings): String =
        "${s.displayName.ifBlank { "Phone" }.take(16)}-${s.originShort}"

    private fun refreshHealth() {
        repo.meshHealth.value = MeshHealth.check(this, radioError)
    }

    /** Bluetooth, Wi-Fi or location changed: re-check, and restart the radios if one came back on. */
    private val radioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val before = repo.meshHealth.value
            refreshHealth()
            val after = repo.meshHealth.value
            val cameBack = (!before.bluetoothOn && after.bluetoothOn) || (!before.wifiOn && after.wifiOn)
            if (cameBack || (radioError != null && !after.blocking)) restartRadios()
        }
    }

    private fun restartRadios() {
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(1_500) // let the adapter settle
            val t = transport ?: return@launch
            Log.i(TAG, "restarting radios")
            t.stop()
            t.start()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerRadioReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(this, radioReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun batteryPercent(): Int =
        getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: 255

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifeline:mesh").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    // ------------------------------------------------------------------ persistence

    /** Writes everything the router reports into Room, which the UI observes. */
    private inner class Persist : RouterListener {
        private val db get() = repo.database

        private fun write(block: suspend () -> Unit) {
            scope.launch(dbWrites) {
                try { block() } catch (e: Exception) { Log.e(TAG, "db write failed", e) }
            }
        }

        override fun onPacket(packet: Packet, raw: ByteArray, fromPeer: String?, linkHops: Int, mine: Boolean) {
            if (!Router.storable(packet)) return
            val now = System.currentTimeMillis()
            write {
                db.packets().insert(packet.toEntity(raw, fromPeer, linkHops, mine, now))
                if (packet is Packet.Ack) db.packets().markAcked(packet.refId, packet.etaMinutes)
                val who = "%08x".format(packet.header.origin).takeLast(4)
                db.metrics().insert(MetricEntity(name = "packet_bytes", value = raw.size.toDouble(), unit = "bytes", backend = null,
                    simulated = false, timestamp = now, notes = "${Router.label(packet)} ${if (mine) "sent" else "from $who"}"))
                if (!mine) {
                    db.metrics().insert(MetricEntity(name = "hops", value = linkHops.toDouble(), unit = "hops", backend = null,
                        simulated = false, timestamp = now, notes = "${Router.label(packet)} from $who"))
                }
            }
        }

        override fun onEvent(event: MeshEvent) {
            val now = System.currentTimeMillis()
            write { db.events().insert(EventEntity(time = now, kind = event.kind.name, text = event.text, packetId = event.packetId)) }
        }

        override fun onReply(ack: Packet.Ack) {
            scope.launch {
                Haptics.strong(this@MeshService)
                showReply(ack)
            }
        }

        override fun onRoundTrip(refId: Long, responder: Int, millis: Long, hops: Int) {
            val now = System.currentTimeMillis()
            write {
                db.metrics().insert(MetricEntity(name = "rtt_ms", value = millis.toDouble(), unit = "ms", backend = null,
                    simulated = false, timestamp = now, notes = "to %s over %d hops".format("%08x".format(responder).takeLast(4), hops)))
            }
        }

        override fun onHandedOff(messageId: Long, peers: Int) {
            write { db.packets().setHandedTo(messageId, peers) }
        }

        override fun onTopology(nodes: List<TopologyNode>) {
            val wallNow = System.currentTimeMillis()
            val clockNow = SystemClock.elapsedRealtime()
            val rows = nodes.map {
                TopologyEntity(
                    origin = it.origin, name = it.name, role = it.role.code, battery = it.battery,
                    neighbors = it.neighbors.joinToString(","), lastHeard = wallNow - (clockNow - it.lastHeard), hops = it.hops,
                )
            }
            write { db.topology().replaceAll(rows) }
        }
    }

    // ------------------------------------------------------------------ notifications

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESH, getString(R.string.notif_channel_mesh), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REPLIES, getString(R.string.notif_channel_replies), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
            }
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun meshNotification(connected: Int): Notification {
        val text = if (connected == 0) getString(R.string.notif_mesh_searching)
        else resources.getQuantityString(R.plurals.notif_mesh_relaying, connected, connected)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MeshService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_MESH)
            .setSmallIcon(R.drawable.ic_hub)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.notif_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun goForeground(connected: Int): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_MESH, meshNotification(connected), type)
        true
    } catch (e: Exception) {
        // e.g. ForegroundServiceStartNotAllowedException when the system restarts us in the background.
        Log.e(TAG, "cannot start foreground", e)
        false
    }

    private var lastConnected = -1

    private fun updateNotification(connected: Int) {
        if (connected == lastConnected) return
        lastConnected = connected
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_MESH, meshNotification(connected)) }
    }

    private fun showReply(ack: Packet.Ack) {
        val text = getString(R.string.notif_reply, ack.etaMinutes) + if (ack.note.isNotBlank()) " · ${ack.note}" else ""
        val n = NotificationCompat.Builder(this, CHANNEL_REPLIES)
            .setSmallIcon(R.drawable.ic_health_and_safety)
            .setContentTitle(getString(R.string.status_help_coming))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_REPLY, n) }
    }
}
