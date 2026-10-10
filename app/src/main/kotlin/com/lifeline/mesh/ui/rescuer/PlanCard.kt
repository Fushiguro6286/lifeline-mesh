package com.lifeline.mesh.ui.rescuer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.ai.PlanLine
import com.lifeline.mesh.ai.PlanStop
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.routing.RoutePlanner
import com.lifeline.mesh.triage.RankedAlert
import com.lifeline.mesh.ui.components.severityLabelRes
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Your plan" on the Rescue tab of a team phone (10 Oct 2026, on request): the jobs this team said "help is coming" to,
 * in the order it should do them (most urgent first, then the one accepted first: Responders.queues, the same rule every
 * phone uses, so the senders' ETAs match), with the minutes for each leg along the roads and when each is reached, and
 * any reported blocked roads the route goes around. Kotlin computes all of it; the on-device model only explains it
 * ("Written on device"), checked number by number.
 */
@Composable
fun PlanCard(settings: Settings, ranked: List<RankedAlert>) {
    if (!settings.role.isTeam) return
    val c = Lifeline.colors
    val app = LocalContext.current.applicationContext as LifelineApp
    val repo = app.repository
    val packets by repo.packets.collectAsStateWithLifecycle()
    val done by repo.jobsDone.collectAsStateWithLifecycle()
    val fix by repo.myFix.collectAsStateWithLifecycle()
    val roads by repo.roads.collectAsStateWithLifecycle()
    val aiStatus by app.ai.status.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val queue = remember(packets, done) {
        com.lifeline.mesh.triage.Responders.distinctStops(queuesFrom(packets, now, done, mine = true).values.firstOrNull().orEmpty())
    }
    if (queue.isEmpty()) return
    val blocks = remember(packets) { blocksFrom(packets) }
    val me = fix
    val urgency = (0..3).associateWith { stringResource(severityLabelRes(it)) }
    val labels = queue.map { h ->
        ranked.firstOrNull { r -> r.alert.reports.any { it.id == h.alertId } }?.let { SituationReportLabels.what(it) } ?: "an alert"
    }
    val stops by produceState(emptyList<PlanStop>(), queue.map { it.alertId }, me?.let { Math.round(it.lat * 4000) to Math.round(it.lon * 4000) },
        roads, blocks) {
        val from = me ?: return@produceState
        value = withContext(Dispatchers.Default) {
            var at = from.lat to from.lon
            var total = 0
            queue.mapIndexedNotNull { i, h ->
                val to = (h.lat ?: return@mapIndexedNotNull null) to (h.lon ?: return@mapIndexedNotNull null)
                val p = RoutePlanner.plan(roads, at, emptyList(), to, settings.travel, blocks)
                total += p.minutes; at = to
                PlanStop(i + 1, urgency.getValue(h.severity.coerceIn(0, 3)), labels[i], p.minutes, total, p.avoidedBlocks)
            }
        }
    }

    // After the first pick-up: the quickest open shelter for this team's way of travelling (11 Oct 2026).
    val shelters = remember(packets) {
        com.lifeline.mesh.triage.Shelters.places(packets.filter {
            it.type == com.lifeline.mesh.packet.PacketType.REPORT.code && now - it.receivedAt < ACTIVE_MS && it.lat != null && it.lon != null
        }.map { com.lifeline.mesh.triage.PlaceReport(it.id, it.reportType ?: 0, it.lat!!, it.lon!!, it.note.orEmpty(), it.receivedAt) })
    }
    val first = queue.firstOrNull()
    val drop by produceState<Pair<String, Int>?>(null, first?.alertId, shelters, roads, blocks) {
        val la = first?.lat; val lo = first?.lon
        value = if (la == null || lo == null) null else withContext(Dispatchers.Default) {
            com.lifeline.mesh.ui.send.quickestShelter(shelters, la to lo, settings.travel, roads, blocks)
                ?.let { (s, p) -> com.lifeline.mesh.ui.send.shelterName(s) to p.minutes }
        }
    }

    var phrased by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(stops, aiStatus) { phrased = null; if (stops.isNotEmpty()) phrased = app.ai.plan(stops) }

    Column(
        Modifier.fillMaxWidth().background(c.surface, Shapes.card).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text("Your plan", style = Lifeline.type.title, color = c.ink)
        if (me == null) Text("Waiting for your position to work out the route.", style = Lifeline.type.body, color = c.inkMuted)
        stops.forEach { s ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Text("${s.order}", style = Lifeline.type.title, color = if (s.urgency == urgency[3]) c.sos else c.ink, modifier = Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text("${s.urgency} · ${s.what}", style = Lifeline.type.bodyStrong, color = c.ink)
                    Text("${s.legMinutes} min · reached after about ${s.arriveMinutes} min" +
                        if (s.avoided > 0) " · around ${s.avoided} blocked road" + (if (s.avoided > 1) "s" else "") else "",
                        style = Lifeline.type.caption, color = if (s.avoided > 0) c.warn else c.inkMuted)
                    if (s.order == 1) drop?.let { (name, m) ->
                        Text("Then take them to $name: about $m min (suggested drop-off, open shelter)",
                            style = Lifeline.type.caption, color = c.safe)
                    }
                }
            }
        }
        if (stops.isNotEmpty()) {
            Text(phrased ?: PlanLine.template(stops), style = Lifeline.type.body, color = c.ink)
            Text((if (phrased != null) "Written on device · " else "") +
                "Most urgent first. Times along the offline map's roads at assumed speeds: estimates.",
                style = Lifeline.type.caption, color = c.inkMuted)
        }
    }
}

/** Plain words for what an alert needs, for the plan card. */
private object SituationReportLabels {
    fun what(r: RankedAlert): String = com.lifeline.mesh.ai.SituationReport.needLabels(r.alert.needs) +
        if (r.alert.people > 0) ", ${r.alert.people} ${if (r.alert.people == 1) "person" else "people"}" else ""
}
