package com.lifeline.mesh.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.net.toUri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.lifeline.mesh.R
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space

/** A group of runtime permissions shown as one checklist row. Empty [permissions] = not needed on this Android. */
private data class PermissionGroup(
    val key: String,
    @StringRes val title: Int,
    @StringRes val why: Int,
    @StringRes val denied: Int,
    val permissions: List<String>,
    /** The permission that decides "granted" (e.g. precise location, not approximate). */
    val decisive: List<String> = permissions,
)

// Permission names newer than minSdk are compile-time constants and only used behind SDK checks.
@SuppressLint("InlinedApi")
private fun permissionGroups(): List<PermissionGroup> {
    val sdk = Build.VERSION.SDK_INT
    val nearby = buildList {
        if (sdk >= 31) addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
        if (sdk >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        // Android 17: needed by Nearby's Wi-Fi LAN medium when targeting API 37.
        if (sdk >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
    }
    return listOf(
        PermissionGroup("nearby", R.string.perm_nearby_title, R.string.perm_nearby_why, R.string.perm_nearby_denied, nearby),
        PermissionGroup(
            "location", R.string.perm_location_title, R.string.perm_location_why, R.string.perm_location_denied,
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            decisive = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
        ),
        PermissionGroup(
            "notifications", R.string.perm_notif_title, R.string.perm_notif_why, R.string.perm_notif_denied,
            if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
        ),
        PermissionGroup("mic", R.string.perm_mic_title, R.string.perm_mic_why, R.string.perm_mic_denied,
            listOf(Manifest.permission.RECORD_AUDIO)),
    ).filter { it.permissions.isNotEmpty() }
}

private fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

private fun Context.bluetoothAdapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

private fun Context.isLocationOn(): Boolean =
    getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) ?: false

private fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: false

private fun Context.startFirst(vararg intents: Intent) {
    for (i in intents) {
        try { startActivity(i); return } catch (_: ActivityNotFoundException) { } catch (_: SecurityException) { }
    }
}

private fun Context.appDetailsIntent() =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())

/**
 * The permissions checklist (DESIGN.md 8.1 page 3): each row has one plain sentence, an action, and a
 * green tick when done. If something is denied, the row says what stops working and how to fix it.
 * States refresh whenever the screen resumes (e.g. coming back from system settings).
 */
@Composable
fun PermissionsChecklist(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var refresh by rememberSaveable { mutableIntStateOf(0) }
    var asked by rememberSaveable { mutableStateOf(setOf<String>()) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.let { asked = asked + it }
        pending = null
        refresh++
    }
    val groups = permissionGroups()

    // Keyed on `refresh` so every check below re-runs after a resume or a permission result.
    key(refresh) { Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        groups.forEach { g ->
            val ok = g.decisive.all { context.granted(it) }
            val wasAsked = g.key in asked
            val permanentlyDenied = !ok && wasAsked &&
                g.permissions.none { p -> activity?.shouldShowRequestPermissionRationale(p) == true }
            ChecklistRow(
                title = stringResource(g.title),
                why = stringResource(g.why),
                done = ok,
                problem = if (!ok && wasAsked) stringResource(g.denied) else null,
                actionLabel = stringResource(if (permanentlyDenied) R.string.perm_open_settings else R.string.perm_grant),
                onAction = {
                    if (permanentlyDenied) context.startFirst(context.appDetailsIntent())
                    else { pending = g.key; launcher.launch(g.permissions.toTypedArray()) }
                },
            )
        }

        val bt = context.bluetoothAdapter()
        if (bt != null) {
            val on = runCatching { bt.isEnabled }.getOrDefault(false)
            ChecklistRow(
                title = stringResource(R.string.perm_bt_title),
                why = stringResource(R.string.perm_bt_why),
                done = on,
                problem = if (!on) stringResource(R.string.perm_bt_off) else null,
                actionLabel = stringResource(R.string.perm_turn_on),
                onAction = {
                    val canRequest = Build.VERSION.SDK_INT < 31 || context.granted(Manifest.permission.BLUETOOTH_CONNECT)
                    if (canRequest) context.startFirst(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    else context.startFirst(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                },
            )
        }

        val locOn = context.isLocationOn()
        ChecklistRow(
            title = stringResource(R.string.perm_locsvc_title),
            why = stringResource(R.string.perm_locsvc_why),
            done = locOn,
            problem = if (!locOn) stringResource(R.string.perm_locsvc_off) else null,
            actionLabel = stringResource(R.string.perm_turn_on),
            onAction = { context.startFirst(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        )

        val batteryOk = context.isIgnoringBatteryOptimizations()
        ChecklistRow(
            title = stringResource(R.string.perm_battery_title),
            why = stringResource(R.string.perm_battery_why),
            done = batteryOk,
            problem = null,
            note = stringResource(R.string.perm_battery_vivo),
            actionLabel = stringResource(R.string.perm_allow),
            onAction = {
                context.startFirst(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            },
            secondaryLabel = stringResource(R.string.perm_battery_more),
            onSecondary = { context.startFirst(context.appDetailsIntent()) },
        )
    } }
}

@Composable
private fun ChecklistRow(
    title: String,
    why: String,
    done: Boolean,
    problem: String?,
    actionLabel: String,
    onAction: () -> Unit,
    note: String? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val c = Lifeline.colors
    val doneLabel = stringResource(R.string.perm_done)
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface, Shapes.card)
            .padding(Space.l),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Box(Modifier.padding(top = 2.dp).size(24.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Icon(painterResource(R.drawable.ic_check_circle_filled), contentDescription = doneLabel, tint = c.safe)
                problem != null -> Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = c.warn)
                else -> Box(Modifier.size(20.dp).border(2.dp, c.outline, Shapes.circle))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(title, style = Lifeline.type.bodyStrong, color = c.ink)
            Text(why, style = Lifeline.type.body, color = c.inkMuted)
            if (problem != null && !done) Text(problem, style = Lifeline.type.body, color = c.warn)
            if (note != null) Text(note, style = Lifeline.type.caption, color = c.inkMuted)
            if (!done || secondaryLabel != null) {
                FlowRow(
                    Modifier.padding(top = Space.s),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    if (!done) PrimaryButton(actionLabel, onAction, Modifier.semantics { contentDescription = "$actionLabel: $title" })
                    if (secondaryLabel != null && onSecondary != null) SecondaryButton(secondaryLabel, onSecondary)
                }
            }
        }
    }
}

/** True when Nearby Connections can advertise and discover (see [com.lifeline.mesh.mesh.MeshHealth.requiredPermissions]). */
fun meshPermissionsGranted(context: Context): Boolean =
    com.lifeline.mesh.mesh.MeshHealth.missingPermissions(context).isEmpty()
