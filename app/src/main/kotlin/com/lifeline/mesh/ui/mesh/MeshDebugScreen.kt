package com.lifeline.mesh.ui.mesh

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.common.GoogleApiAvailability
import com.lifeline.mesh.AppRepository
import com.lifeline.mesh.data.EventEntity
import com.lifeline.mesh.data.PacketEntity
import com.lifeline.mesh.data.Settings as AppSettings
import com.lifeline.mesh.mesh.LinkState
import com.lifeline.mesh.mesh.MeshEvent
import com.lifeline.mesh.mesh.MeshHealth
import com.lifeline.mesh.mesh.MeshService
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.Packet
import com.lifeline.mesh.packet.PacketType
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 2 debug panel (English only, a developer screen): this phone, problems with fixes, mesh controls,
 * direct neighbors, the topology learned from Hellos, recent packets, measurements and the event log.
 * The Live Mesh screen replaces it on the Mesh tab in Phase 3.
 */
@Composable
fun MeshDebugScreen(settings: AppSettings, repository: AppRepository, onPermissions: () -> Unit) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val running by repository.meshRunning.collectAsStateWithLifecycle()
    val neighbors by repository.neighbors.collectAsStateWithLifecycle()
    val serviceHealth by repository.meshHealth.collectAsStateWithLifecycle()
    val packets by repository.packets.collectAsStateWithLifecycle()
    val events by repository.events.collectAsStateWithLifecycle()
    val topology by repository.topology.collectAsStateWithLifecycle()
    val metrics by repository.metrics.collectAsStateWithLifecycle()

    // Re-check radios and permissions whenever the screen comes back (e.g. from system settings).
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumes++; onPauseOrDispose { } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val health = remember(resumes, serviceHealth, now / 5_000) { MeshHealth.check(context, serviceHealth.radioError) }

    val myLatest = packets.firstOrNull { it.isMine && it.type == PacketType.ALERT.code }
    val latestReceived = packets.firstOrNull { !it.isMine && it.type == PacketType.ALERT.code }
    val connected = neighbors.count { it.state == LinkState.CONNECTED }
    val names = remember(topology) { topology.associate { it.origin to it.name } }
    fun who(origin: Int) = names[origin]?.takeIf { it.isNotBlank() }?.let { "$it-${hex4(origin)}" } ?: hex4(origin)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        item {
            Card {
                Text("${settings.displayName.ifBlank { "Phone" }}-${settings.originShort}", style = Lifeline.type.headline, color = c.ink)
                Text("Origin %08x · role %s".format(settings.originId, settings.role.name.lowercase().replace('_', ' ')),
                    style = Lifeline.type.caption, color = c.inkMuted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Dot(if (!running) c.inkMuted else if (connected > 0) c.link else c.warn)
                    Text(
                        when {
                            !running -> "Mesh not running"
                            connected == 0 -> "Running · looking for phones"
                            else -> "Running · $connected connected"
                        },
                        style = Lifeline.type.bodyStrong, color = c.ink,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (running) SecondaryButton("Stop mesh", { MeshService.stop(context) })
                    else PrimaryButton("Start mesh", { repository.userStoppedMesh = false; MeshService.start(context) }, enabled = health.permissionsOk)
                }
            }
        }

        val problems = problems(health)
        if (problems.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                problems.forEach { p ->
                    Card(tone = if (p.blocking) c.sos else c.warn) {
                        Text(p.text, style = Lifeline.type.body, color = c.ink)
                        if (p.action != null) SecondaryButton(p.action, { p.fix(context, onPermissions) })
                    }
                }
            }
        }

        item {
            Card {
                Heading("Test")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    PrimaryButton("Send test alert", { sendTestAlert(repository, settings) }, color = c.sos)
                    SecondaryButton("Send ping", { myLatest?.let { repository.sendPing(it.id) } })
                    if (latestReceived != null) SecondaryButton("Reply to latest alert", {
                        repository.originate { h -> Packet.Ack(h, latestReceived.id, 20, "Test reply") }
                    })
                }
                if (myLatest == null) Text("Send ping needs one of your alerts first.", style = Lifeline.type.caption, color = c.inkMuted)
                if (myLatest != null) {
                    val status = when {
                        myLatest.acked -> "Help is coming · ETA ${myLatest.ackEta} min"
                        myLatest.handedTo > 0 -> "Passed to ${myLatest.handedTo} nearby phone${if (myLatest.handedTo == 1) "" else "s"}"
                        else -> "Waiting for a nearby phone"
                    }
                    Text("Your last alert (${ago(now, myLatest.receivedAt)}): $status", style = Lifeline.type.bodyStrong,
                        color = if (myLatest.acked) c.safe else c.ink)
                }
            }
        }

        item {
            Card {
                Heading("Direct neighbors (${neighbors.size})")
                if (neighbors.isEmpty()) Text("None yet. Keep the other phones within a few metres, with this app open.",
                    style = Lifeline.type.caption, color = c.inkMuted)
                neighbors.forEach { n ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Dot(when (n.state) { LinkState.CONNECTED -> c.link; LinkState.CONNECTING -> c.warn; LinkState.DISCOVERED -> c.signal; LinkState.LOST -> c.inkMuted })
                        Text(n.name, style = Lifeline.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                        Text("${n.state.name.lowercase()} · ${ago(now, n.lastHeard)}", style = Lifeline.type.caption, color = c.inkMuted)
                    }
                }
            }
        }

        item {
            Card {
                Heading("Mesh from Hellos (${topology.size})")
                if (topology.isEmpty()) Text("No Hello received yet. Hellos go out every 10 s.", style = Lifeline.type.caption, color = c.inkMuted)
                topology.sortedBy { it.hops }.forEach { t ->
                    val nb = t.neighbors.split(',').filter { it.isNotBlank() }.joinToString { s -> who(s.toInt()) }
                    Text("${who(t.origin)} · ${t.hops} hop${if (t.hops == 1) "" else "s"} · ${roleName(t.role)} · " +
                        "${if (t.battery == 255) "battery ?" else "${t.battery}%"} · ${ago(now, t.lastHeard)}",
                        style = Lifeline.type.bodyStrong, color = c.ink)
                    Text("neighbors: ${nb.ifBlank { "none" }}", style = Lifeline.type.caption, color = c.inkMuted)
                }
            }
        }

        item {
            Card {
                Heading("Measured")
                val rtt = metrics.firstOrNull { it.name == "rtt_ms" && !it.simulated }
                val hops = metrics.firstOrNull { it.name == "hops" && !it.simulated }
                val bytes = metrics.firstOrNull { it.name == "packet_bytes" && !it.simulated }
                Text("Round trip: ${rtt?.let { "${it.value.toLong()} ms (${it.notes})" } ?: "Not measured"}", style = Lifeline.type.body, color = c.ink)
                Text("Last hops: ${hops?.let { "${it.value.toInt()} (${it.notes})" } ?: "Not measured"}", style = Lifeline.type.body, color = c.ink)
                Text("Last packet: ${bytes?.let { "${it.value.toInt()} bytes (${it.notes})" } ?: "Not measured"}", style = Lifeline.type.body, color = c.ink)
            }
        }

        item { Heading("Packets (${packets.size})") }
        items(packets.take(15), key = { "p${it.id}" }) { p -> PacketRow(p, now, ::who) }

        item { Heading("Events") }
        items(events.take(150), key = { "e${it.rowId}" }) { e -> EventRow(e) }
        item { Text(" ", modifier = Modifier.padding(bottom = Space.xl)) }
    }
}

private fun sendTestAlert(repository: AppRepository, settings: AppSettings) {
    val name = settings.displayName.ifBlank { "Phone" }
    repository.originate { h ->
        // No GPS yet (Phase 4): position unknown, accuracy 255.
        Packet.Alert(h, 0, 0, 255, needs = Need.TRAPPED.bit or Need.MEDICAL.bit, severity = 3, people = 3,
            note = "Test alert from $name")
    }
}

// ------------------------------------------------------------------ problems

private class Problem(val text: String, val blocking: Boolean, val action: String?, val fix: (Context, () -> Unit) -> Unit = { _, _ -> })

private fun problems(h: MeshHealth): List<Problem> = buildList {
    if (!h.permissionsOk) add(Problem("Nearby devices permission is missing. The mesh cannot start.", true, "Grant permissions") { _, open -> open() })
    if (!h.playServicesOk) add(Problem(
        "Google Play services is missing or out of date (${GoogleApiAvailability.getInstance().getErrorString(h.playServices)}). " +
            "Nearby Connections runs inside it; update it while you still have internet.", true, "Open Play services") { ctx, _ ->
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.android.gms"))) }
    })
    if (h.hasBluetooth && !h.bluetoothOn) add(Problem(
        if (h.wifiOn) "Bluetooth is off. Phones find each other much more slowly over Wi-Fi alone." else "Bluetooth and Wi-Fi are both off. No phone can be reached.",
        !h.wifiOn, "Bluetooth settings") { ctx, _ -> ctx.open(Settings.ACTION_BLUETOOTH_SETTINGS) })
    if (!h.wifiOn) add(Problem("Wi-Fi is off. The mesh still works over Bluetooth, but transfers are slower. No internet is needed.",
        false, "Wi-Fi settings") { ctx, _ -> ctx.open(Settings.ACTION_WIFI_SETTINGS) })
    if (!h.locationOn) add(Problem("Location is off. Many phones cannot find nearby phones without it.",
        false, "Turn on location") { ctx, _ -> ctx.open(Settings.ACTION_LOCATION_SOURCE_SETTINGS) })
    if (!h.batteryExempt) add(Problem(
        "Battery optimization is on: this phone stops relaying about 10 s after the screen turns off. " +
            "Allow it, and on vivo/iQOO also allow background activity in the app's battery settings.",
        false, "Allow in background") { ctx, _ ->
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}")))
        }.onFailure { ctx.open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
    })
    h.radioError?.let { add(Problem(it, true, null)) }
}

private fun Context.open(action: String) {
    runCatching { startActivity(Intent(action)) }
}

// ------------------------------------------------------------------ rows

@Composable
private fun PacketRow(p: PacketEntity, now: Long, who: (Int) -> String) {
    val c = Lifeline.colors
    val type = PacketType.fromCode(p.type)?.name?.lowercase()?.replace('_', '-') ?: "type ${p.type}"
    val detail = when (p.type) {
        PacketType.ALERT.code -> "sev ${p.severity} · ${p.people} people · ${Need.fromMask(p.needs ?: 0).joinToString { it.label }}" +
            (if (p.acked) " · acked ETA ${p.ackEta}" else "")
        PacketType.ACK.code -> "for %016x · ETA %d".format(p.refId ?: 0, p.eta ?: 0)
        else -> ""
    }
    Column(Modifier.fillMaxWidth().background(c.surface, Shapes.card).padding(Space.m)) {
        Text(
            "${if (p.isMine) "Mine" else "From ${who(p.origin)}"} · $type · ${if (p.isMine) "sent" else "${p.hops} hop${if (p.hops == 1) "" else "s"}"} · ${p.rawBytes.size} B",
            style = Lifeline.type.label, color = if (p.isMine) c.signal else c.ink,
        )
        if (detail.isNotEmpty()) Text(detail, style = Lifeline.type.caption, color = c.inkMuted)
        if (!p.note.isNullOrBlank()) Text("\"${p.note}\"", style = Lifeline.type.caption, color = c.ink)
        Text("%016x · %s".format(p.id, ago(now, p.receivedAt)), style = mono(), color = c.inkMuted)
    }
}

@Composable
private fun EventRow(e: EventEntity) {
    val c = Lifeline.colors
    val tone = when (e.kind) {
        MeshEvent.Kind.DUPLICATE.name, MeshEvent.Kind.RATE_LIMITED.name, MeshEvent.Kind.MALFORMED.name, MeshEvent.Kind.TTL_END.name -> c.inkMuted
        MeshEvent.Kind.REPLY.name -> c.safe
        MeshEvent.Kind.QUEUED.name -> c.warn
        MeshEvent.Kind.CONNECTED.name, MeshEvent.Kind.DISCONNECTED.name -> c.link
        else -> c.ink
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(TIME.get()!!.format(Date(e.time)), style = mono(), color = c.inkMuted)
        Text(e.text, style = Lifeline.type.caption, color = tone)
    }
}

// ------------------------------------------------------------------ small pieces

@Composable
private fun Card(tone: Color? = null, content: @Composable () -> Unit) {
    val c = Lifeline.colors
    Column(
        Modifier.fillMaxWidth().background(c.surface, Shapes.card)
            .then(if (tone != null) Modifier.border(2.dp, tone, Shapes.card) else Modifier)
            .padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) { content() }
}

@Composable
private fun Heading(text: String) =
    Text(text, style = Lifeline.type.title, color = Lifeline.colors.ink, modifier = Modifier.semantics { heading() })

@Composable
private fun Dot(color: Color) = androidx.compose.foundation.layout.Box(Modifier.size(10.dp).background(color, CircleShape))

@Composable
private fun mono() = Lifeline.type.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

private val TIME = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }

private fun hex4(origin: Int) = "%08x".format(origin).takeLast(4)

private fun roleName(code: Int) = when (code) { 1 -> "needs help"; 2 -> "volunteer"; 3 -> "rescuer"; else -> "role $code" }

private fun ago(now: Long, then: Long): String {
    val s = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        else -> "${s / 3600}h ago"
    }
}
