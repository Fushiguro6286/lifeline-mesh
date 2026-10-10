package com.lifeline.mesh.safety

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** The live Bluetooth link to a watch, band, oximeter or blood pressure cuff (10 Oct 2026, on request). */
sealed interface WatchState {
    data object None : WatchState
    data class Connecting(val name: String, val simulated: Boolean) : WatchState
    data class Connected(val name: String, val since: Long, val simulated: Boolean) : WatchState
    /** The link dropped: health is not being watched. The phone keeps trying to reconnect. */
    data class Lost(val name: String, val at: Long, val simulated: Boolean) : WatchState
    /** Connected, but the device offers none of the standard health services. */
    data class NoHealthData(val name: String) : WatchState
}

/** Address used for the simulated watch (simulation mode only). */
const val SIMULATED_WATCH = "SIMULATED"

/** One device found by [WatchScanner]: what it says it measures, from its advertised services. */
data class FoundWatch(val address: String, val name: String, val heartRate: Boolean, val oxygen: Boolean, val bloodPressure: Boolean)

private fun Context.bluetoothAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (
    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

/** Looks for devices advertising the standard heart rate, pulse oximeter or blood pressure service. */
class WatchScanner(context: Context) {
    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private var callback: ScanCallback? = null

    enum class Problem { NO_BLUETOOTH, NOT_ALLOWED, OFF }

    fun problem(): Problem? = when {
        adapter == null -> Problem.NO_BLUETOOTH
        !app.bluetoothAllowed() -> Problem.NOT_ALLOWED
        !adapter.isEnabled -> Problem.OFF
        else -> null
    }

    @SuppressLint("MissingPermission") // checked in problem()
    fun start(onFound: (FoundWatch) -> Unit): Boolean {
        if (problem() != null) return false
        val scanner = adapter?.bluetoothLeScanner ?: return false
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val uuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
                val d = result.device
                onFound(FoundWatch(d.address, d.name ?: result.scanRecord?.deviceName ?: d.address,
                    BleVitals.HEART_RATE_SERVICE in uuids, BleVitals.PULSE_OXIMETER_SERVICE in uuids, BleVitals.BLOOD_PRESSURE_SERVICE in uuids))
            }
        }
        val filters = BleVitals.SERVICES.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        runCatching { scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb) }
            .onFailure { Log.w("WatchScanner", "scan failed", it); return false }
        callback = cb
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        callback = null
    }
}

/**
 * Keeps one watch connected and passes every measurement to [onReading] as it arrives (real time, about once a second
 * for heart rate). When the link drops the state becomes [WatchState.Lost] and the phone reconnects by itself as soon as
 * the watch is back in range (autoConnect). The simulated watch (simulation mode only) behaves the same way and can be
 * told to show low oxygen or to lose the link, for rehearsals and the stage demo; everything it sends is labelled
 * Simulated on screen.
 */
class WatchLink(context: Context, private val scope: CoroutineScope, private val onReading: (VitalReading) -> Unit) {
    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val _state = MutableStateFlow<WatchState>(WatchState.None)
    val state: StateFlow<WatchState> = _state

    private var gatt: BluetoothGatt? = null
    private var wanted: Pair<String, String>? = null
    private val toEnable = ArrayDeque<BluetoothGattCharacteristic>()

    private var simJob: Job? = null
    /** Simulated watch mode: "normal", "low_oxygen" or "high_heart". */
    @Volatile private var simMode = "normal"
    @Volatile private var simLinked = true

    /** Connects to [address] ([SIMULATED_WATCH] = the simulated watch); null = disconnect and forget. */
    fun use(address: String?, name: String) {
        if (wanted?.first == address) return
        stop()
        if (address == null) return
        wanted = address to name
        if (address == SIMULATED_WATCH) startSimulated(name) else connect(address, name, autoConnect = false)
    }

    fun stop() {
        wanted = null
        simJob?.cancel(); simJob = null
        closeGatt()
        _state.value = WatchState.None
    }

    // ------------------------------------------------------------------ real Bluetooth LE

    @SuppressLint("MissingPermission")
    private fun connect(address: String, name: String, autoConnect: Boolean) {
        val a = adapter ?: return
        if (!app.bluetoothAllowed()) { Log.w(TAG, "Bluetooth permission missing"); return }
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return
        if (!autoConnect) _state.value = WatchState.Connecting(name, simulated = false)
        gatt = device.connectGatt(app, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        toEnable.clear()
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val (address, name) = wanted ?: run { runCatching { g.close() }; return }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> { g.discoverServices() }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                    val was = _state.value
                    if (was !is WatchState.NoHealthData) {
                        // Only a link that worked can be "lost"; a first attempt that fails stays "connecting" and retries.
                        if (was is WatchState.Connected || was is WatchState.Lost) {
                            _state.value = WatchState.Lost((was as? WatchState.Connected)?.name ?: name,
                                (was as? WatchState.Lost)?.at ?: System.currentTimeMillis(), simulated = false)
                            Log.i(TAG, "watch link lost (status $status); reconnecting when in range")
                        } else Log.i(TAG, "could not connect (status $status); retrying when in range")
                        // autoConnect: Android connects again by itself when the watch is back in range.
                        scope.launch { delay(1_000); if (wanted?.first == address) connect(address, name, autoConnect = true) }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val name = wanted?.second ?: return
            toEnable.clear()
            g.services.flatMap { it.characteristics }.filter { it.uuid in BleVitals.MEASUREMENTS }.forEach { toEnable.addLast(it) }
            if (toEnable.isEmpty()) {
                _state.value = WatchState.NoHealthData(name)
                runCatching { g.disconnect() }
                return
            }
            _state.value = WatchState.Connected(name, System.currentTimeMillis(), simulated = false)
            enableNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = enableNext(g)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            deliver(c.uuid, value)

        @Deprecated("Android 12 and older deliver the value on the characteristic")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) deliver(c.uuid, c.value ?: return)
        }
    }

    private fun deliver(uuid: java.util.UUID, value: ByteArray) {
        BleVitals.decode(uuid, value, System.currentTimeMillis()).forEach(onReading)
    }

    /** One descriptor write at a time (GATT allows only one operation in flight); the next one in onDescriptorWrite. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun enableNext(g: BluetoothGatt) {
        val c = toEnable.removeFirstOrNull() ?: return
        val cccd = c.getDescriptor(BleVitals.CCCD) ?: return enableNext(g)
        g.setCharacteristicNotification(c, true)
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, value)
        else { cccd.value = value; g.writeDescriptor(cccd) }
    }

    // ------------------------------------------------------------------ simulated watch (simulation mode only)

    private fun startSimulated(name: String) {
        simMode = "normal"; simLinked = true
        _state.value = WatchState.Connecting(name, simulated = true)
        simJob = scope.launch {
            delay(1_500)
            _state.value = WatchState.Connected(name, System.currentTimeMillis(), simulated = true)
            var tick = 0
            while (isActive) {
                delay(1_000)
                tick++
                if (!simLinked) continue
                val now = System.currentTimeMillis()
                val hr = when (simMode) {
                    "low_oxygen" -> 108.0 + Random.nextInt(-3, 4)
                    "high_heart" -> 165.0 + Random.nextInt(-4, 5)
                    else -> 74.0 + Random.nextInt(-3, 4)
                }
                onReading(VitalReading(VitalReading.Kind.HEART_RATE, hr, now))
                // An oximeter streams every second; a calm watch reports oxygen every 5 s.
                if (simMode == "low_oxygen") onReading(VitalReading(VitalReading.Kind.OXYGEN, 85.0 + Random.nextInt(0, 2), now))
                else if (tick % 5 == 0) onReading(VitalReading(VitalReading.Kind.OXYGEN, 97.0 + Random.nextInt(0, 2), now))
            }
        }
    }

    /** Simulated watch: "low_oxygen" (about 85 %), "high_heart" (about 165 per minute) or "normal". */
    fun simulate(mode: String) { if (wanted?.first == SIMULATED_WATCH) simMode = mode }

    /** Simulated watch: the link drops for 20 s, then comes back, like a real watch going out of range and returning. */
    fun simulateLoss() {
        val name = wanted?.second ?: return
        if (wanted?.first != SIMULATED_WATCH || !simLinked) return
        simLinked = false
        _state.value = WatchState.Lost(name, System.currentTimeMillis(), simulated = true)
        scope.launch {
            delay(20_000)
            if (wanted?.first == SIMULATED_WATCH) {
                simLinked = true
                _state.value = WatchState.Connected(name, System.currentTimeMillis(), simulated = true)
            }
        }
    }

    private companion object { const val TAG = "WatchLink" }
}
