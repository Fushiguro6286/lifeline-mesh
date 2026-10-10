package com.lifeline.mesh.ui.mesh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeline.mesh.AppRepository
import com.lifeline.mesh.R
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.mesh.Bridge
import com.lifeline.mesh.mesh.GapPhone
import com.lifeline.mesh.mesh.Gaps
import com.lifeline.mesh.mesh.PowerMode
import com.lifeline.mesh.mesh.RelayGaps
import com.lifeline.mesh.mesh.WeakPoint
import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.PacketType
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.rescuer.ACTIVE_MS
import com.lifeline.mesh.ui.rescuer.distanceText
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.delay

/**
 * The relay gaps this phone can see, recomputed every 15 s and whenever the mesh changes. Lost phones (and where to
 * stand) only on rescue-team phones: they show other people's positions, which only teams may see (Phase 10). Weak
 * points name phones only, as the Mesh tab already does, so every phone sees them.
 */
@Composable
fun rememberGaps(settings: Settings, repo: AppRepository): Gaps {
    val positions by repo.positions.collectAsStateWithLifecycle()
    val topology by repo.topology.collectAsStateWithLifecycle()
    val origins by repo.neighborOrigins.collectAsStateWithLifecycle()
    val fix by repo.myFix.collectAsStateWithLifecycle()
    val metrics by repo.metrics.collectAsStateWithLifecycle()
    val packets by repo.packets.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    return remember(positions, topology, origins, fix, metrics, packets, settings.role, settings.simulationMode, now) {
        val me = GapPhone(settings.originId, settings.displayName, settings.role.hello, fix?.lat, fix?.lon, now)
        val phones = if (!settings.role.isTeam) emptyList() else positions.values.filter { !it.viaInternet }.map {
            GapPhone(it.origin, it.name, it.role, it.lat, it.lon, it.heardAt)
        }
        val links = origins.values.map { settings.originId to it } +
            topology.flatMap { t -> t.neighbors.split(',').mapNotNull { it.trim().toIntOrNull() }.map { t.origin to it } }
        val names = topology.associate { it.origin to it.name } + positions.values.associate { it.origin to it.name }
        val roles = topology.mapNotNull { t -> HelloRole.fromCode(t.role)?.let { t.origin to it } }.toMap() +
            positions.values.associate { it.origin to it.role }
        val batteries = topology.filter { it.battery in 0..100 }.associate { it.origin to it.battery }
        val alerts = packets.filter { it.type == PacketType.ALERT.code && now - it.receivedAt < ACTIVE_MS }
        val open = alerts.filter { a -> packets.none { it.type == PacketType.CHECK_IN.code && it.origin == a.origin && it.receivedAt > a.receivedAt } }
            .mapTo(HashSet()) { it.origin }
        val range = metrics.filter { it.name == "link_range_m" && it.simulated == settings.simulationMode }.maxOfOrNull { it.value }
        RelayGaps.find(me, phones, open, links, names, roles, batteries, range, now,
            if (settings.simulationMode) RelayGaps.SIM_SILENT_MS else RelayGaps.SILENT_MS)
    }
}

/**
 * The Mesh tab line for relay gaps. Something to act on now (a lost phone, or a phone that is your only link and is
 * saving battery) gets the amber banner. Single links on their own are normal in a line-shaped mesh, so they get a
 * quiet one-line row instead, still one tap from the full list.
 */
@Composable
fun GapsBanner(gaps: Gaps, onShow: () -> Unit) {
    if (gaps.isEmpty) return
    val c = Lifeline.colors
    val urgent = gaps.bridges.size + gaps.weak.count { it.mode != PowerMode.NORMAL }
    if (urgent > 0) {
        Row(
            Modifier.fillMaxWidth().background(c.surface, Shapes.card).border(1.dp, c.signal, Shapes.card).padding(Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Icon(painterResource(R.drawable.ic_hub), null, tint = c.signal, modifier = Modifier.size(20.dp))
            Text(urgentText(gaps), style = Lifeline.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
            SecondaryButton("Show", onShow)
        }
    } else {
        val n = gaps.weak.size
        Row(
            Modifier.fillMaxWidth().background(c.surface.copy(alpha = 0.85f), Shapes.card).padding(horizontal = Space.m, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(if (n == 1) "1 phone is your only link to others" else "$n phones are your only link to others",
                style = Lifeline.type.caption, color = c.inkMuted, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onShow) { Text("Show", style = Lifeline.type.label, color = c.link) }
        }
    }
}

/** "2 phones lost · 1 link low on battery". */
fun urgentText(g: Gaps): String = listOfNotNull(
    g.bridges.size.takeIf { it > 0 }?.let { if (it == 1) "1 phone lost" else "$it phones lost" },
    g.weak.count { it.mode != PowerMode.NORMAL }.takeIf { it > 0 }?.let { if (it == 1) "1 only link low on battery" else "$it only links low on battery" },
).joinToString(" · ")

/** Every gap as plain sentences, most urgent first. */
@Composable
fun GapsList(gaps: Gaps, simulated: Boolean) {
    val c = Lifeline.colors
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(bottom = Space.xxl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text("Relay gaps", style = Lifeline.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })
        if (simulated) Text("Simulated", style = Lifeline.type.label, color = c.warn)
        if (gaps.isEmpty) Text("No gaps: every phone heard in the last 30 minutes can still be reached, and no single phone is your only link to others.",
            style = Lifeline.type.body, color = c.inkMuted)
        gaps.bridges.forEach { b -> GapItem(bridgeTitle(b), bridgeBody(b, gaps), if (b.hasAlert) c.sos else c.signal) }
        gaps.weak.forEach { w -> GapItem(weakTitle(w), weakBody(w), if (w.mode == PowerMode.NORMAL) c.signal else c.warn) }
        if (gaps.spots.isNotEmpty()) Text(
            "In all, ${if (gaps.spots.size == 1) "one person" else "${gaps.spots.size} people"} at the marked " +
                "${if (gaps.spots.size == 1) "spot" else "spots"} on the Map tab would link these phones again: " +
                "one person can link several where their paths meet.",
            style = Lifeline.type.bodyStrong, color = c.ink)
        Text(rangeNote(gaps), style = Lifeline.type.caption, color = c.inkMuted)
        Text("Worked out on this phone from the positions and hellos it has heard; distances are straight lines between GPS positions.",
            style = Lifeline.type.caption, color = c.inkMuted)
    }
}

@Composable
private fun GapItem(title: String, body: String, tone: androidx.compose.ui.graphics.Color) {
    val c = Lifeline.colors
    Column(
        Modifier.fillMaxWidth().background(c.surfaceRaised, Shapes.card).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(title, style = Lifeline.type.bodyStrong, color = tone)
        Text(body, style = Lifeline.type.body, color = c.ink)
    }
}

private fun who(p: GapPhone) = p.name.ifBlank { "%08x".format(p.origin).takeLast(4) }

private fun minutes(ms: Long) = (ms / 60_000).coerceAtLeast(1)

fun bridgeTitle(b: Bridge): String =
    "${who(b.phone)}'s phone went quiet ${minutes(b.silentMs)} min ago" + if (b.hasAlert) " · open alert" else ""

fun bridgeBody(b: Bridge, g: Gaps): String {
    val fromPhone = when {
        b.fromKind == com.lifeline.mesh.mesh.Anchor.SPOT -> "the person at the nearest marked spot"
        b.fromKind == com.lifeline.mesh.mesh.Anchor.RELINKED -> "${who(b.from)}'s phone once it is linked again"
        b.from.origin == g.myOrigin -> "your phone"
        else -> "${who(b.from)}'s phone"
    }
    val from = if (b.from.lat == null) "" else "${distanceText(b.distanceM)} from $fromPhone"
    val flat = b.phone.battery?.takeIf { it <= 10 }?.let { ", out of battery (it last said $it %)" } ?: ", out of battery"
    return when {
        b.tooFar -> "$from: too far for a chain of phones. Send a team to its last position, or reach it through the server."
        b.relays == 0 -> "Only $from, within the range links reach here. It may be switched off$flat or behind walls."
        else -> "$from. ${if (b.relays == 1) "One person" else "${b.relays} people"} with the app standing at the marked " +
            "${if (b.relays == 1) "spot" else "spots"} (Map tab), about ${distanceText(g.spacingM)} apart, would link it again."
    }
}

fun weakTitle(w: WeakPoint): String = "${who(w.phone)}'s phone is your only link to " +
    (if (w.cutOff.size == 1) w.cutOff[0] else "${w.cutOff.size} phones") +
    (if (w.mode != PowerMode.NORMAL && w.phone.battery != null) " · battery ${w.phone.battery} %" else "")

fun weakBody(w: WeakPoint): String {
    val list = if (w.cutOff.size == 1) {
        if (w.teams == 1) "A rescue team. " else ""
    } else {
        w.cutOff.dropLast(1).joinToString(", ") + " and " + w.cutOff.last() +
            when (w.teams) { 0 -> ". "; 1 -> ", one of them a rescue team. "; else -> ", ${w.teams} of them rescue teams. " }
    }
    val battery = when (w.mode) {
        PowerMode.CRITICAL -> "It is almost out of battery and passes on urgent alerts and replies only. "
        PowerMode.SAVER -> "It is saving battery. "
        PowerMode.NORMAL -> ""
    }
    return list + "If it stops, you lose ${if (w.cutOff.size == 1) "them" else "them all"}. " + battery +
        "A second phone with the app near ${who(w.phone)} would keep them connected."
}

fun rangeNote(g: Gaps): String =
    if (g.rangeMeasured) "Spacing: 80 % of the longest link this phone measured (${distanceText(g.rangeM)})."
    else "No link length measured yet: assuming ${distanceText(g.rangeM)} (Bluetooth outdoors), so ${distanceText(g.spacingM)} apart."
