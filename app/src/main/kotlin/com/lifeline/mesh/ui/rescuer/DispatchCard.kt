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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.mesh.PositionBeacon
import com.lifeline.mesh.packet.PacketType
import com.lifeline.mesh.triage.Assignment
import com.lifeline.mesh.triage.Dispatch
import com.lifeline.mesh.triage.DispatchJob
import com.lifeline.mesh.triage.DispatchTeam
import com.lifeline.mesh.triage.RankedAlert
import com.lifeline.mesh.triage.Teams
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.components.severityLabelRes
import com.lifeline.mesh.ui.onboarding.teamLabel
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space

/** At most this many jobs on the card: the most urgent ones. */
private const val SHOWN = 6

/**
 * "Who goes where" on a team phone's Rescue tab (10 Oct 2026): the open alerts split between the rescue teams this
 * phone can see (Dispatch: most urgent first, the right type of team that gets there soonest, jobs already accepted
 * stay). Every team phone runs the same rules, so they suggest the same split from the same reports and positions.
 * A job suggested for this phone has a button to say "help is coming" with that estimate.
 */
@Composable
fun DispatchCard(settings: Settings, ranked: List<RankedAlert>) {
    if (!settings.role.isTeam) return
    val c = Lifeline.colors
    val repo = (LocalContext.current.applicationContext as LifelineApp).repository
    val packets by repo.packets.collectAsStateWithLifecycle()
    val positions by repo.positions.collectAsStateWithLifecycle()
    val fix by repo.myFix.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()

    val split = remember(ranked, packets, positions, fix?.let { Math.round(it.lat * 4000) to Math.round(it.lon * 4000) }, settings.role) {
        val teams = positions.values.filter { it.role.isTeam && now - it.heardAt < PositionBeacon.SHOW_MS }
            .map { DispatchTeam(it.origin, it.name, it.role, it.lat, it.lon) } +
            listOfNotNull(fix?.let { DispatchTeam(settings.originId, "You", settings.role.hello, it.lat, it.lon) })
        val acks = packets.filter { it.type == PacketType.ACK.code && it.refId != null }
        val jobs = ranked.mapNotNull { r ->
            val pos = r.alert.position
            val ids = r.alert.reports.map { it.id }.toSet()
            val taker = acks.filter { it.refId in ids }.minByOrNull { it.receivedAt }?.origin
            DispatchJob(r.alert.id, r.rank, r.alert.needs, r.alert.severity, pos?.lat, pos?.lon, taker)
        }
        Dispatch.split(jobs, teams)
    }
    if (split.isEmpty()) return
    val urgency = (0..3).associateWith { stringResource(severityLabelRes(it)) }
    val teamNames = (3..7).associateWith { stringResource(teamLabel(it)) }

    Column(
        Modifier.fillMaxWidth().background(c.surface, Shapes.card).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text("Who goes where", style = Lifeline.type.title, color = c.ink)
        split.take(SHOWN).forEach { a -> JobRow(a, ranked, urgency, teamNames, settings.originId, onTake = { minutes ->
            val r = ranked.firstOrNull { it.alert.id == a.job.alertId } ?: return@JobRow
            repo.sendAcks(r.alert.reports.map { it.id }, minutes, "")
        }) }
        Text((if (settings.simulationMode) "Simulated · " else "") +
            "Suggested by the same rules on every team phone: most urgent first, then the team of the right type that gets " +
            "there soonest, counting its earlier jobs and ${Dispatch.ON_SCENE_MIN} min at each. Straight-line estimates from " +
            "the positions this phone has heard.",
            style = Lifeline.type.caption, color = if (settings.simulationMode) c.warn else c.inkMuted)
    }
}

@Composable
private fun JobRow(
    a: Assignment, ranked: List<RankedAlert>, urgency: Map<Int, String>, teamNames: Map<Int, String>, me: Int, onTake: (Int) -> Unit,
) {
    val c = Lifeline.colors
    val r = ranked.firstOrNull { it.alert.id == a.job.alertId } ?: return
    val what = com.lifeline.mesh.ai.SituationReport.needLabels(r.alert.needs) +
        if (r.alert.people > 0) ", ${r.alert.people} ${if (r.alert.people == 1) "person" else "people"}" else ""
    val mine = a.team?.origin == me
    val teamText = a.team?.let { t -> if (mine) "You" else "${t.name.ifBlank { "A team" }} (${teamNames[t.role.code] ?: "team"})" }
    val line = when {
        a.taken -> "${teamText ?: "A team"} said help is coming"
        a.team == null -> "No team in reach"
        else -> "→ $teamText" + (a.minutes?.let { " · about $it min" } ?: "") +
            if (!a.rightType) " · no ${teamNames[Teams.teamFor(r.alert.needs)?.code ?: 3]?.lowercase() ?: "matching team"} in reach" else ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Text("#${a.job.rank}", style = Lifeline.type.title, color = if (a.job.severity >= 3) c.sos else c.ink, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text("${urgency.getValue(a.job.severity.coerceIn(0, 3))} · $what", style = Lifeline.type.bodyStrong, color = c.ink)
            Text(line, style = Lifeline.type.body, color = when { a.taken -> c.inkMuted; mine -> c.link; else -> c.ink })
        }
        if (mine && !a.taken) SecondaryButton("Take it", { onTake(a.minutes ?: 10) })
    }
}
