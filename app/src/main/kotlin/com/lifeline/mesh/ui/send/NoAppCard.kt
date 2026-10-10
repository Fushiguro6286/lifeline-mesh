package com.lifeline.mesh.ui.send

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.lifeline.mesh.R
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renames this phone's Bluetooth adapter to an SOS line and puts the original name back afterwards. The original is
 * kept in SharedPreferences, so it survives the app being closed while the SOS name is showing.
 */
class BluetoothSosBeacon(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("bluetooth_sos", Context.MODE_PRIVATE)
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    val active: Boolean get() = prefs.contains(KEY_ORIGINAL)

    private fun allowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Why the beacon cannot start, or null when it can. */
    fun problem(): Int? = when {
        adapter == null -> R.string.noapp_bt_missing
        !allowed() -> R.string.noapp_bt_permission
        !adapter.isEnabled -> R.string.noapp_bt_off
        else -> null
    }

    @Suppress("MissingPermission") // checked in problem()
    fun start(sosName: String): Boolean {
        val a = adapter ?: return false
        if (problem() != null) return false
        if (!active) prefs.edit().putString(KEY_ORIGINAL, a.name ?: "").apply()
        return runCatching { a.setName(sosName) }.onFailure { Log.w(TAG, "setName failed", it) }.getOrDefault(false)
    }

    @Suppress("MissingPermission")
    fun stop() {
        val original = prefs.getString(KEY_ORIGINAL, null) ?: return
        val a = adapter
        if (a != null && allowed() && original.isNotBlank()) runCatching { a.setName(original) }
        prefs.edit().remove(KEY_ORIGINAL).apply()
    }

    @Suppress("MissingPermission")
    fun currentName(): String? = if (allowed()) runCatching { adapter?.name }.getOrNull() else null

    private companion object {
        const val TAG = "BluetoothSos"
        const val KEY_ORIGINAL = "original_name"
    }
}

/** Copies this app's installed APK to the share folder and opens the share sheet (pick Quick Share or Bluetooth). */
private suspend fun shareApp(context: Context) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        File(dir, "LifelineMesh.apk").also { out -> File(context.applicationInfo.sourceDir).copyTo(out, overwrite = true) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.android.package-archive"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.noapp_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * "Phones without the app" card on the SOS tab: show my SOS in nearby Bluetooth lists (one-way, 5 minutes per system
 * prompt), and share the app itself with no internet. [sosName] is built from my latest alert.
 */
@Composable
fun NoAppCard(sosName: String, modifier: Modifier = Modifier) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val beacon = remember { BluetoothSosBeacon(context) }
    var active by remember { mutableStateOf(beacon.active) }
    var visibleUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var sharing by remember { mutableStateOf(false) }
    LaunchedEffect(visibleUntil, sosName) {
        var tick = 0
        while (System.currentTimeMillis() < visibleUntil) {
            delay(1_000); now = System.currentTimeMillis()
            // Nearby Connections renames the adapter for its own Bluetooth discovery (seen on the iQOO 15 ~90 s in).
            // While we are visible, put the SOS name back: nearby strangers must see it. Nearby still finds phones
            // over BLE and Wi-Fi meanwhile.
            if (++tick % 3 == 0 && beacon.active && beacon.currentName() != sosName) beacon.start(sosName)
        }
    }

    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        // RESULT_CANCELED = the person said no; any other code is the number of seconds the phone stays visible.
        if (r.resultCode != Activity.RESULT_CANCELED) {
            now = System.currentTimeMillis(); visibleUntil = now + r.resultCode * 1000L
        }
    }
    fun show() {
        problem = beacon.problem()
        if (problem != null) return
        if (!beacon.start(sosName)) { problem = R.string.noapp_bt_failed; return }
        active = true
        discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, VISIBLE_S))
    }

    Column(
        modifier.fillMaxWidth().background(c.surface, Shapes.card).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Icon(painterResource(R.drawable.ic_bluetooth), null, tint = c.link, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.noapp_title), style = Lifeline.type.title, color = c.ink)
        }
        Text(stringResource(R.string.noapp_body), style = Lifeline.type.body, color = c.inkMuted)
        val left = ((visibleUntil - now) / 1000).coerceAtLeast(0)
        if (active) {
            Text(stringResource(R.string.noapp_showing, beacon.currentName() ?: sosName), style = Lifeline.type.bodyStrong, color = c.link)
            Text(
                if (left > 0) stringResource(R.string.noapp_visible_for, left / 60, left % 60) else stringResource(R.string.noapp_not_visible),
                style = Lifeline.type.caption, color = c.inkMuted,
            )
            if (left == 0L) PrimaryButton(stringResource(R.string.noapp_show_again), ::show, Modifier.fillMaxWidth())
            SecondaryButton(stringResource(R.string.noapp_stop), { beacon.stop(); active = false; visibleUntil = 0 }, Modifier.fillMaxWidth())
        } else {
            PrimaryButton(stringResource(R.string.noapp_show), ::show, Modifier.fillMaxWidth())
        }
        problem?.let { Text(stringResource(it), style = Lifeline.type.body, color = c.sos) }
        SecondaryButton(
            stringResource(if (sharing) R.string.noapp_share_preparing else R.string.noapp_share),
            {
                if (!sharing) scope.launch {
                    sharing = true
                    runCatching { shareApp(context) }.onFailure { Log.w("NoAppCard", "share failed", it) }
                    sharing = false
                }
            },
            Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.noapp_share_hint), style = Lifeline.type.caption, color = c.inkMuted)
    }
}

/** The system caps a discoverable request at 300 seconds (BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION). */
private const val VISIBLE_S = 300
