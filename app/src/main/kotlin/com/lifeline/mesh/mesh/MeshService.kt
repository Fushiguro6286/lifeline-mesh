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
import com.lifeline.mesh.metrics.BatteryMonitor
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
import kotlinx.coroutines.flow.collectLatest
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
        private const val CHANNEL_AMBULANCE = "ambulance"
        private const val CHANNEL_FALL = "fall"
        private const val CHANNEL_WARNINGS = "warnings"
        private const val CHANNEL_WATCH = "watch"
        private const val NOTIF_WATCH = 6
        private const val NOTIF_WARNING = 5
        private const val NOTIF_AMBULANCE = 3
        private const val NOTIF_FALL = 4
        private const val ACTION_FALL_OK = "com.lifeline.mesh.FALL_OK"
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
    private var fallSensor: com.lifeline.mesh.safety.FallSensor? = null
    private var alarm: com.lifeline.mesh.safety.AlarmEffects? = null
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
        watchForFalls()
        watchVitals()
    }

    // ------------------------------------------------------------------ fall and crash detection

    /**
     * While the setting is on, the accelerometer feeds [com.lifeline.mesh.safety.FallDetector]. A fall (or "Try the fall
     * alarm") starts a 15 s countdown here, in the service, so it also runs with the screen off: alarm sound, vibration and
     * a notification with "I'm OK". Nobody answers: a panic SOS with this phone's position goes out over the mesh.
     */
    private fun watchForFalls() {
        val sensor = com.lifeline.mesh.safety.FallSensor(this) {
            Log.i(TAG, "fall detected")
            repo.startFallAlarm(test = false)
        }
        fallSensor = sensor
        val effects = com.lifeline.mesh.safety.AlarmEffects(this)
        alarm = effects
        scope.launch {
            repo.settings.filterNotNull().map { it.fallDetection }.distinctUntilChanged().collect { on ->
                if (on) { if (!sensor.start()) Log.w(TAG, "no accelerometer: fall detection unavailable") } else sensor.stop()
            }
        }
        scope.launch {
            repo.fallAlarm.collectLatest { a ->
                val nm = getSystemService(NotificationManager::class.java)
                if (a == null) { effects.stop(); nm.cancel(NOTIF_FALL); return@collectLatest }
                effects.start()
                try {
                    while (true) {
                        val left = a.secondsLeft(System.currentTimeMillis())
                        if (left <= 0) break
                        runCatching { nm.notify(NOTIF_FALL, fallNotification(left, a.test)) }
                        delay(1_000)
                    }
                } finally {
                    effects.stop()
                    nm.cancel(NOTIF_FALL)
                }
                if (a.vitals != null) sendVitalsRequest(a.vitals) else sendFallSos()
                repo.fallAlarm.compareAndSet(a, null)
            }
        }
    }

    /**
     * Watch vitals alarm (10 Oct 2026): while the setting is on and Health Connect allows it, read the watch's synced
     * readings every minute; one far outside normal ([com.lifeline.mesh.safety.VitalsCheck]) starts the same 15 s alarm
     * as a fall. Each reading raises the alarm at most once.
     */
    private fun watchVitals() {
        val vitals = com.lifeline.mesh.safety.WatchVitals(this)
        val fromHealthConnect = kotlinx.coroutines.flow.MutableStateFlow<List<com.lifeline.mesh.safety.VitalReading>>(emptyList())
        // Health Connect (delayed, many watches): read every minute while the alarm is on.
        scope.launch {
            repo.settings.filterNotNull().map { it.watchVitals }.distinctUntilChanged().collectLatest { on ->
                if (!on) { fromHealthConnect.value = emptyList(); return@collectLatest }
                while (true) {
                    if (vitals.status() == com.lifeline.mesh.safety.WatchVitals.Status.READY) {
                        val now = System.currentTimeMillis()
                        fromHealthConnect.value = vitals.read(now - com.lifeline.mesh.safety.VitalsCheck.MAX_AGE_MS)
                        repo.lastVitalsCheck.value = now to fromHealthConnect.value.size
                    }
                    delay(60_000)
                }
            }
        }
        // Check both sources every 2 s (the Bluetooth stream is real time). After an alarm, a quiet period.
        scope.launch {
            var handledUpTo = System.currentTimeMillis() - com.lifeline.mesh.safety.VitalsCheck.MAX_AGE_MS
            while (true) {
                delay(2_000)
                if (repo.settings.value?.watchVitals != true) continue
                val now = System.currentTimeMillis()
                com.lifeline.mesh.safety.VitalsCheck.check(fromHealthConnect.value + repo.liveVitals.value, now, handledUpTo)?.let { a ->
                    handledUpTo = a.readingAtMs + com.lifeline.mesh.safety.VitalsCheck.QUIET_MS
                    Log.i(TAG, "watch vitals alarm: ${a.reason}")
                    repo.startFallAlarm(test = false, vitals = a.reason)
                }
            }
        }
        watchOverBluetooth()
    }

    private var watchLink: com.lifeline.mesh.safety.WatchLink? = null

    /**
     * The live Bluetooth watch (10 Oct 2026): kept connected while the mesh runs, readings into repo.liveVitals as they
     * arrive. When the link drops: a notification and vibration ("health is not being watched"), and the phone reconnects
     * when the watch is back. The simulated watch exists only in simulation mode.
     */
    private fun watchOverBluetooth() {
        val link = com.lifeline.mesh.safety.WatchLink(this, scope) { r ->
            val now = System.currentTimeMillis()
            repo.liveVitals.value = (repo.liveVitals.value + r)
                .filter { now - it.atMs <= com.lifeline.mesh.safety.VitalsCheck.MAX_AGE_MS }.takeLast(4_000)
        }
        watchLink = link
        scope.launch {
            repo.settings.filterNotNull().map { Triple(it.watchAddress, it.watchName, it.simulationMode) }.distinctUntilChanged()
                .collect { (address, name, sim) ->
                    val simulatedWatch = address == com.lifeline.mesh.safety.SIMULATED_WATCH
                    if (simulatedWatch && !sim) { link.use(null, ""); repo.setWatch(null); return@collect } // never outside simulation
                    if (!simulatedWatch && sim) { link.use(null, ""); return@collect } // a real watch waits until simulation ends
                    if (address == null) repo.liveVitals.value = emptyList()
                    link.use(address, name)
                }
        }
        scope.launch {
            var before: com.lifeline.mesh.safety.WatchState = com.lifeline.mesh.safety.WatchState.None
            link.state.collect { st ->
                repo.watchState.value = st
                val nm = getSystemService(NotificationManager::class.java)
                if (st is com.lifeline.mesh.safety.WatchState.Lost && before is com.lifeline.mesh.safety.WatchState.Connected) {
                    Haptics.strong(this@MeshService)
                    val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(st.at))
                    val n = NotificationCompat.Builder(this@MeshService, CHANNEL_WATCH)
                        .setSmallIcon(R.drawable.ic_health_and_safety)
                        .setContentTitle(getString(R.string.watch_lost_title) + if (st.simulated) " · ${getString(R.string.simulated_tag)}" else "")
                        .setContentText(getString(R.string.watch_lost_body, st.name, at))
                        .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.watch_lost_body, st.name, at)))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setContentIntent(openApp())
                        .build()
                    runCatching { nm.notify(NOTIF_WATCH, n) }
                }
                if (st is com.lifeline.mesh.safety.WatchState.Connected || st is com.lifeline.mesh.safety.WatchState.None) nm.cancel(NOTIF_WATCH)
                before = st
            }
        }
        scope.launch {
            repo.simWatchCommands.collect { c ->
                when (c) {
                    "low_oxygen" -> link.simulateLowOxygen(true)
                    "normal" -> link.simulateLowOxygen(false)
                    "lose" -> link.simulateLoss()
                }
            }
        }
    }

    /** Nobody answered the watch alarm: an urgent ambulance request (ambulance phones ring) with my position. */
    private suspend fun sendVitalsRequest(reason: String) {
        val fix = repo.myFix.value ?: com.lifeline.mesh.location.LocationProvider(this).lastKnown()
        val note = com.lifeline.mesh.packet.PacketCodec.trimToBytes("Watch: $reason, no answer", com.lifeline.mesh.packet.PacketCodec.MAX_ALERT_NOTE)
        val draft = com.lifeline.mesh.ai.AlertDraft(setOf(com.lifeline.mesh.packet.Need.MEDICAL, com.lifeline.mesh.packet.Need.AMBULANCE), 1, 3, note)
        repo.originate { h ->
            com.lifeline.mesh.ui.send.SendViewModel.alertPacket(h, draft, com.lifeline.mesh.ai.LocationChoice(fix, approximate = true))
        }
        Haptics.strong(this)
        Log.i(TAG, "watch vitals ambulance request sent")
    }

    private suspend fun sendFallSos() {
        val fix = repo.myFix.value ?: com.lifeline.mesh.location.LocationProvider(this).lastKnown()
        val draft = com.lifeline.mesh.ai.QualityGate.panicDraft().copy(note = "Fall or crash detected, no answer")
        repo.originate { h ->
            com.lifeline.mesh.ui.send.SendViewModel.alertPacket(h, draft, com.lifeline.mesh.ai.LocationChoice(fix, approximate = true))
        }
        Haptics.strong(this)
        Log.i(TAG, "fall SOS sent")
    }

    private fun fallNotification(secondsLeft: Int, test: Boolean): Notification {
        val ok = PendingIntent.getService(
            this, 2, Intent(this, MeshService::class.java).setAction(ACTION_FALL_OK), PendingIntent.FLAG_IMMUTABLE,
        )
        val vitals = repo.fallAlarm.value?.vitals
        val text = if (vitals != null) getString(R.string.vitals_countdown, secondsLeft) else getString(R.string.fall_countdown, secondsLeft)
        val b = NotificationCompat.Builder(this, CHANNEL_FALL)
            .setSmallIcon(R.drawable.ic_sos)
            .setContentTitle((if (vitals != null) getString(R.string.vitals_title) else getString(R.string.fall_title)) +
                if (test) " · ${getString(if (vitals != null) R.string.vitals_test else R.string.fall_test)}" else "")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.fall_ok), ok)
        // Lights the screen and opens the countdown over the lock screen where the phone allows it.
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()) b.setFullScreenIntent(openApp(), true)
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FALL_OK) {
            repo.cancelFallAlarm()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            repo.userStoppedMesh = true
            stopSelf()
            return START_NOT_STICKY
        }
        repo.userStoppedMesh = false
        return START_STICKY
    }

    override fun onDestroy() {
        fallSensor?.stop()
        watchLink?.stop()
        alarm?.stop()
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

        // The drill happens around this phone (its fix now, or the last known one), so the demo looks right anywhere.
        val center = if (simulated) repo.myFix.value ?: com.lifeline.mesh.location.LocationProvider(this).lastKnown() else null
        val simulation = if (simulated) MeshSimulation(s, SystemClock::elapsedRealtime, wall,
            centerLat = center?.lat ?: MeshSimulation.LAT, centerLon = center?.lon ?: MeshSimulation.LON) else null
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
        router.autoPing = true
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
        // Live position (Phase 10): GPS fixes into the repository; my Position beacon when PositionBeacon says so.
        // In simulation the virtual phones are around the venue, so "me" is there too unless GPS knows better.
        s.launch { com.lifeline.mesh.location.LocationProvider(this@MeshService).updates().collect { repo.myFix.value = it } }
        s.launch {
            var last: PositionBeacon.Sent? = null
            while (isActive) {
                val fix = repo.myFix.value ?: simulation?.let { com.lifeline.mesh.ai.GeoFix(it.centerLat, it.centerLon, 50f, 0) }
                val now = SystemClock.elapsedRealtime()
                if (fix != null && PositionBeacon.due(last, fix.lat, fix.lon, now)) {
                    router.sendPosition(com.lifeline.mesh.packet.Geo.toE6(fix.lat), com.lifeline.mesh.packet.Geo.toE6(fix.lon),
                        com.lifeline.mesh.packet.Geo.accuracyStep(fix.accuracyM))
                    last = PositionBeacon.Sent(fix.lat, fix.lon, now)
                }
                delay(5_000)
            }
        }

        // Battery for Hellos every minute; on real phones also the drain while relaying (CLAUDE.md 10), stored every
        // 5 minutes once 15 minutes on battery have passed. Never in simulation: it would be this phone's drain anyway.
        val battery = if (simulated) null else BatteryMonitor()
        s.launch {
            var lastStored = 0L
            while (isActive) {
                delay(60_000)
                val pct = batteryPercent()
                router.battery = pct
                val now = System.currentTimeMillis()
                val d = battery?.onSample(BatteryMonitor.Sample(now, pct, isCharging())) ?: continue
                if (now - lastStored < 5 * 60_000L) continue
                lastStored = now
                val linked = repo.neighbors.value.count { it.state == LinkState.CONNECTED }
                val note = "${d.fromPct}% to ${d.toPct}% over ${d.minutes} min while relaying, $linked phones connected now"
                launch(dbWrites) {
                    repo.database.metrics().insert(MetricEntity(name = "battery_drain", value = d.pctPerHour, unit = "%/h",
                        backend = null, simulated = false, timestamp = now, notes = note))
                }
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
        repo.positions.value = emptyMap()
        repo.jobsDone.value = emptySet()
        scope.launch(dbWrites) { repo.database.topology().clear() }
        lastConnected = -1
    }

    private fun applySettings(r: Router, s: Settings) {
        r.displayName = s.displayName
        r.role = s.role.hello
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

    private fun isCharging(): Boolean = getSystemService(BatteryManager::class.java)?.isCharging ?: false

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

        private var bestRangeM = 0.0

        /** A new farthest direct link (GPS distance to a phone heard over one link) becomes a measurement. */
        private fun recordLinkRange(p: PeerPosition, linkHops: Int) {
            val f = repo.myFix.value ?: return
            val r = LinkRanges.measure(p, linkHops, f.lat, f.lon, f.accuracyM, f.timeMs, p.heardAt) ?: return
            val best = maxOf(bestRangeM, repo.metrics.value.filter { it.name == "link_range_m" }.maxOfOrNull { it.value } ?: 0.0)
            if (r.metres <= best + 1) return
            bestRangeM = r.metres
            write { db.metrics().insert(metric("link_range_m", r.metres, "m", "to ${r.peerName} ±${r.uncertaintyM.toInt()} m (GPS)", p.heardAt)) }
        }

        private fun metric(name: String, value: Double, unit: String, notes: String, at: Long) =
            MetricEntity(name = name, value = value, unit = unit, backend = null, simulated = simulated, timestamp = at, notes = notes)

        override fun onPacket(packet: Packet, raw: ByteArray, fromPeer: String?, linkHops: Int, mine: Boolean) {
            if (packet is Packet.Position) {
                if (!mine) {
                    val p = PeerPosition(
                        origin = packet.header.origin, name = packet.name, role = packet.role,
                        lat = packet.latE6 / 1e6, lon = packet.lonE6 / 1e6,
                        accuracyM = com.lifeline.mesh.packet.Geo.accuracyMeters(packet.accuracy),
                        hops = linkHops, heardAt = System.currentTimeMillis(),
                    )
                    repo.positions.value = repo.positions.value + (p.origin to p)
                    recordLinkRange(p, linkHops)
                }
                return
            }
            if (!Router.storable(packet)) return
            if (packet is Packet.Warning && !mine) scope.launch { warningAlarm(packet, viaInternet = fromPeer == Router.INTERNET) }
            if (packet is Packet.Alert && !mine) {
                val role = repo.settings.value?.role
                val ambulance = packet.needs and com.lifeline.mesh.packet.Need.AMBULANCE.bit != 0 && role == com.lifeline.mesh.data.Role.AMBULANCE
                val assigned = fromPeer == Router.INTERNET && role?.isTeam == true && repo.assignedToMe.remove(packet.header.messageId)
                if (ambulance || assigned) scope.launch { ambulanceAlarm(packet, simulated, assignedOnly = !ambulance) }
            }
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

    /**
     * An ambulance request reached an Ambulance phone (Phase 10): alarm-sound notification, strong vibration and, if
     * read-aloud is on, "Ambulance requested. 2 patients. 1.4 km away." Distance from my live fix when known.
     */
    private fun ambulanceAlarm(a: Packet.Alert, simulated: Boolean, assignedOnly: Boolean = false) {
        val fix = repo.myFix.value
        val far = if (fix != null && (a.latE6 != 0 || a.lonE6 != 0))
            com.lifeline.mesh.ui.rescuer.distanceText(com.lifeline.mesh.triage.GeoMath.distanceM(fix.lat, fix.lon, a.latE6 / 1e6, a.lonE6 / 1e6))
        else getString(R.string.notif_amb_far)
        val patients = resources.getQuantityString(R.plurals.amb_patients, a.people, a.people)
        val text = getString(R.string.notif_amb_text, patients, far) + if (a.note.isNotBlank()) " · ${a.note}" else ""
        Haptics.strong(this)
        val n = NotificationCompat.Builder(this, CHANNEL_AMBULANCE)
            .setSmallIcon(R.drawable.ic_emergency)
            .setContentTitle(getString(if (assignedOnly) R.string.notif_assigned_title else R.string.notif_amb_title) +
                if (simulated) " · ${getString(R.string.simulated_tag)}" else "")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_AMBULANCE, n) }
        val s = repo.settings.value
        if (s?.readAloud == true && !assignedOnly) (application as LifelineApp).readAloud.speak(getString(R.string.notif_amb_say, patients, far), s.language.tag)
    }

    /**
     * An official warning arrived (first copy only). Severe or Extreme and still in force: strong vibration, an alarm
     * notification and read aloud. Says how it arrived: from the server, or passed on by nearby phones (not verifiable).
     */
    private fun warningAlarm(w: Packet.Warning, viaInternet: Boolean) {
        if (w.level < 2 || w.expires * 1000 <= System.currentTimeMillis()) return
        Haptics.strong(this)
        val title = getString(when { w.drill -> R.string.warning_title_drill; w.forecast -> R.string.warning_title_forecast
            else -> R.string.warning_title }, w.event.ifBlank { "—" })
        val until = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(w.expires * 1000))
        val how = (when { w.drill -> ""; w.forecast -> getString(R.string.warning_source_forecast) + " · "
            else -> getString(R.string.warning_source) + " · " }) +
            getString(if (viaInternet) R.string.warning_via_internet else R.string.warning_via_mesh)
        val n = NotificationCompat.Builder(this, CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(title)
            .setContentText(w.headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${w.headline}\n${getString(R.string.warning_until, until)} · $how"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_WARNING, n) }
        val s = repo.settings.value
        if (s?.readAloud == true) (application as LifelineApp).readAloud.speak("$title. ${w.headline}", s.language.tag)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCH, getString(R.string.watch_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WARNINGS, getString(R.string.warning_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 400, 1000, 400, 1000)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build(),
                )
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_AMBULANCE, "Ambulance requests", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build(),
                )
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESH, getString(R.string.notif_channel_mesh), NotificationManager.IMPORTANCE_LOW)
        )
        // The fall alarm rings and vibrates itself (on a loop until "I'm OK"), so the channel stays silent.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FALL, getString(R.string.fall_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
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

    /**
     * connectedDevice always; plus location (Phase 10 live position) when permission is granted and location is on.
     * Android refuses the location type when started from the background without background-location permission
     * (developer.android.com, foreground service types): then we fall back to connectedDevice and keep relaying.
     */
    private fun goForeground(connected: Int, simulated: Boolean): Boolean {
        val notif = meshNotification(connected, simulated)
        if (Build.VERSION.SDK_INT < 29) return runCatching { startForeground(NOTIF_MESH, notif) }.isSuccess
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val withLocation = device or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val canLocate = com.lifeline.mesh.location.LocationProvider(this).hasPermission() &&
            getSystemService(LocationManager::class.java)?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } == true
        for (type in if (canLocate) listOf(withLocation, device) else listOf(device)) {
            try {
                ServiceCompat.startForeground(this, NOTIF_MESH, notif, type)
                return true
            } catch (e: Exception) {
                // SecurityException (location not allowed now) or ForegroundServiceStartNotAllowedException.
                Log.w(TAG, "startForeground type $type refused: ${e.javaClass.simpleName}")
            }
        }
        return false
    }

    private var lastConnected = -1

    private fun updateNotification(connected: Int, simulated: Boolean) {
        if (connected == lastConnected) return
        lastConnected = connected
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_MESH, meshNotification(connected, simulated)) }
    }

    private fun showReply(ack: Packet.Ack, simulated: Boolean) {
        val text = getString(R.string.notif_reply, ack.etaMinutes)
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
