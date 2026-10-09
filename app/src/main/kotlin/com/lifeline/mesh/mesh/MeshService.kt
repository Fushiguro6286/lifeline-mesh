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
import com.lifeline.mesh.withLocale
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service (type connectedDevice) that owns the transport, [Router] and [HelloBeacon], so relaying
 * continues with the screen off or the app in the background (CLAUDE.md 4 and 11).
 *
 * Runs one "session" at a time: real ([NearbyTransport]) or simulated ([MeshSimulation], CLAUDE.md 7.5).
 * Switching simulation mode in Settings ends one session and starts the other. Everything a simulated session
 * writes is flagged simulated, so it never mixes with real packets, events or metrics.
 *
 * Holds a partial wake lock while running: without it the CPU sleeps with the screen off and the 50–300 ms
 * forwarding delays and 10 s Hellos stall.
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var restartJob: Job? = null
    @Volatile private var radioError: String? = null
    private var receiverRegistered = false
    private var session: Session? = null

    private class Session(
        val job: Job,
        val router: Router,
        val beacon: HelloBeacon,
        val nearby: NearbyTransport?,
        val simulation: MeshSimulation?,
    ) {
        val simulated get() = simulation != null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Notifications and read-aloud in the app language, not the phone's (they reach a frightened user). */
    override fun attachBaseContext(newBase: Context) {
        val lang = (newBase.applicationContext as LifelineApp).settingsStore.languageBlocking()
        super.attachBaseContext(newBase.withLocale(lang.tag))
    }

    override fun onCreate() {
        super.onCreate()
        repo = (application as LifelineApp).repository
        createChannels()
        if (!goForeground(0, simulated = false)) {
            stopSelf()
            return
        }
        acquireWakeLock()
        registerRadioReceiver()
        scope.launch {
            repo.settings.filterNotNull().map { it.simulationMode }.distinctUntilChanged().collect { simulated ->
                stopSession()
                startSession(simulated)
            }
        }
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
        stopSession()
        if (receiverRegistered) runCatching { unregisterReceiver(radioReceiver) }
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ sessions

    private suspend fun startSession(simulated: Boolean) {
        val settings = repo.settings.filterNotNull().first()
        refreshHealth()
        if (!simulated && !repo.meshHealth.value.permissionsOk) {
            Log.w(TAG, "missing permissions ${repo.meshHealth.value.missingPermissions}; not starting")
            stopSelf()
            return
        }
        val job = SupervisorJob(scope.coroutineContext[Job])
        val s = CoroutineScope(scope.coroutineContext + job)
        val wall = { System.currentTimeMillis() / 1000 }

        val simulation = if (simulated) MeshSimulation(s, SystemClock::elapsedRealtime, wall) else null
        val nearby = if (simulated) null else NearbyTransport(this, s, endpointName(settings)) { err ->
            radioError = err
            refreshHealth()
        }
        val transport: Transport = simulation?.meTransport ?: nearby!!
        val persist = Persist(simulated)
        val router = Router(
            me = settings.originId,
            transport = transport,
            scope = s,
            listener = persist,
            clock = SystemClock::elapsedRealtime,
            wallSeconds = wall,
            peerLabel = { id -> simulation?.label(id) ?: nearby!!.nameOf(id) },
        )
        persist.router = router
        applySettings(router, settings)
        router.battery = batteryPercent()

        val now = System.currentTimeMillis()
        if (simulated) {
            // The virtual phones start with empty memories, so the simulation starts clean too; otherwise this phone
            // would feed them old simulated packets and Kiran would answer old alerts again.
            withContext(dbWrites) {
                repo.database.packets().clearSimulated()
                repo.database.events().clearSimulated()
                repo.database.metrics().clearSimulated()
            }
        }
        val stored = withContext(Dispatchers.IO) { repo.database.packets().since(now - KEEP_MS, simulated) }
        router.restore(stored.map {
            StoredPacket(it.rawBytes, it.isMine, ageMs = now - it.receivedAt, delivered = !it.isMine || it.handedTo > 0)
        })

        val beacon = HelloBeacon(router)
        session = Session(job, router, beacon, nearby, simulation)
        repo.attachRouter(router, simulation)
        repo.waiting.value = router.waitingCount()
        // Router first, radios second: the router must be listening before the first link comes up.
        nearby?.start()
        simulation?.start()
        beacon.start(s)
        repo.meshRunning.value = true
        goForeground(0, simulated)
        Log.i(TAG, "mesh started (${if (simulated) "simulated" else "nearby as ${endpointName(settings)}"}), restored ${stored.size} packets")

        val neighbors: StateFlow<Map<String, Neighbor>> = simulation?.neighbors ?: nearby!!.neighbors
        s.launch {
            neighbors.collect { map ->
                val list = map.values.sortedBy { it.name }
                repo.neighbors.value = list
                repo.neighborOrigins.value = router.neighborOrigins()
                updateNotification(list.count { it.state == LinkState.CONNECTED }, simulated)
            }
        }
        s.launch {
            repo.settings.filterNotNull().map { it.displayName to it.role }.distinctUntilChanged().collect {
                repo.settings.value?.let { st -> applySettings(router, st) }
            }
        }
        s.launch {
            while (isActive) {
                delay(60_000)
                router.battery = batteryPercent()
            }
        }
    }

    private fun stopSession() {
        val old = session ?: return
        session = null
        old.beacon.stop()
        old.nearby?.stop()
        old.simulation?.stop()
        repo.detachRouter(old.router)
        old.job.cancel()
        repo.meshRunning.value = false
        repo.neighbors.value = emptyList()
        repo.neighborOrigins.value = emptyMap()
        repo.waiting.value = 0
        scope.launch(dbWrites) { repo.database.topology().clear() }
        lastConnected = -1
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
            val t = session?.nearby ?: return@launch
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

    /** Writes everything the router reports into Room (flagged [simulated]), which the UI observes. */
    private inner class Persist(private val simulated: Boolean) : RouterListener {
        lateinit var router: Router
        private val db get() = repo.database

        private fun write(block: suspend () -> Unit) {
            scope.launch(dbWrites) {
                try { block() } catch (e: Exception) { Log.e(TAG, "db write failed", e) }
            }
        }

        private fun metric(name: String, value: Double, unit: String, notes: String, at: Long) =
            MetricEntity(name = name, value = value, unit = unit, backend = null, simulated = simulated, timestamp = at, notes = notes)

        override fun onPacket(packet: Packet, raw: ByteArray, fromPeer: String?, linkHops: Int, mine: Boolean) {
            if (!Router.storable(packet)) return
            val now = System.currentTimeMillis()
            write {
                db.packets().insert(packet.toEntity(raw, fromPeer, linkHops, mine, now, simulated))
                if (packet is Packet.Ack) db.packets().markAcked(packet.refId, packet.etaMinutes)
                val who = "%08x".format(packet.header.origin).takeLast(4)
                val what = Router.label(packet)
                db.metrics().insert(metric("packet_bytes", raw.size.toDouble(), "bytes", "$what ${if (mine) "sent" else "from $who"}", now))
                if (!mine) db.metrics().insert(metric("hops", linkHops.toDouble(), "hops", "$what from $who", now))
            }
        }

        override fun onEvent(event: MeshEvent) {
            val now = System.currentTimeMillis()
            write { db.events().insert(EventEntity(time = now, kind = event.kind.name, text = event.text, packetId = event.packetId, simulated = simulated)) }
        }

        override fun onReply(ack: Packet.Ack) {
            scope.launch {
                Haptics.strong(this@MeshService)
                showReply(ack, simulated)
                val s = repo.settings.value
                if (s?.readAloud == true) {
                    val text = getString(R.string.status_help_coming) + ". " + getString(R.string.notif_reply, ack.etaMinutes)
                    (application as LifelineApp).readAloud.speak(text, s.language.tag)
                }
            }
        }

        override fun onRoundTrip(refId: Long, responder: Int, millis: Long, hops: Int) {
            val now = System.currentTimeMillis()
            val note = "to %s over %d hops".format("%08x".format(responder).takeLast(4), hops)
            write { db.metrics().insert(metric("rtt_ms", millis.toDouble(), "ms", note, now)) }
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
            repo.neighborOrigins.value = router.neighborOrigins()
            write { db.topology().replaceAll(rows) }
        }

        override fun onLinkActivity(peer: String, packet: Packet, outgoing: Boolean, linkHops: Int) {
            val origins = router.neighborOrigins()
            repo.neighborOrigins.value = origins
            repo.linkActivity.tryEmit(LinkEvent(peer, origins[peer], outgoing, packet, linkHops, System.currentTimeMillis()))
        }

        override fun onWaiting(count: Int) {
            repo.waiting.value = count
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

    private fun title(simulated: Boolean) =
        if (simulated) "${getString(R.string.app_name)} · ${getString(R.string.simulated_tag)}" else getString(R.string.app_name)

    private fun meshNotification(connected: Int, simulated: Boolean): Notification {
        val text = if (connected == 0) getString(R.string.notif_mesh_searching)
        else resources.getQuantityString(R.plurals.notif_mesh_relaying, connected, connected)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MeshService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_MESH)
            .setSmallIcon(R.drawable.ic_hub)
            .setContentTitle(title(simulated))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.notif_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun goForeground(connected: Int, simulated: Boolean): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_MESH, meshNotification(connected, simulated), type)
        true
    } catch (e: Exception) {
        // e.g. ForegroundServiceStartNotAllowedException when the system restarts us in the background.
        Log.e(TAG, "cannot start foreground", e)
        false
    }

    private var lastConnected = -1

    private fun updateNotification(connected: Int, simulated: Boolean) {
        if (connected == lastConnected) return
        lastConnected = connected
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_MESH, meshNotification(connected, simulated)) }
    }

    private fun showReply(ack: Packet.Ack, simulated: Boolean) {
        val text = getString(R.string.notif_reply, ack.etaMinutes) + if (ack.note.isNotBlank()) " · ${ack.note}" else ""
        val n = NotificationCompat.Builder(this, CHANNEL_REPLIES)
            .setSmallIcon(R.drawable.ic_health_and_safety)
            .setContentTitle(if (simulated) "${getString(R.string.status_help_coming)} · ${getString(R.string.simulated_tag)}" else getString(R.string.status_help_coming))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_REPLY, n) }
    }
}
