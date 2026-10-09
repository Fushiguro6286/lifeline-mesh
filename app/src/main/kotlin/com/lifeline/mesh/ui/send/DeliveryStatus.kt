package com.lifeline.mesh.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.data.PacketEntity
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.components.StatusTimeline
import com.lifeline.mesh.ui.components.StepState
import com.lifeline.mesh.ui.components.TimelineStep
import com.lifeline.mesh.ui.components.labelRes
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space

/**
 * Delivery Status (CLAUDE.md 7.3, DESIGN.md 8.2), honest states only:
 * Created → "Waiting for a nearby phone" or "Passed to N nearby phones" (neighbors that confirmed receipt)
 * → "Help is coming" once an Ack for this alert arrives. Then the card turns safe-green with the ETA.
 */
@Composable
fun DeliveryStatusCard(alert: PacketEntity, ack: PacketEntity?, onSendAnother: () -> Unit, modifier: Modifier = Modifier, language: String = "en") {
    val c = Lifeline.colors
    val context = LocalContext.current
    val acked = alert.acked || ack != null
    val eta = ack?.eta ?: alert.ackEta
    val needs = Need.fromMask(alert.needs ?: 0)
    val what = if (Need.PANIC in needs) stringResource(R.string.status_panic)
    else needs.filter { it != Need.PANIC }.joinToString(" · ") { n -> context.getString(n.labelRes()) }

    Column(
        modifier.fillMaxWidth()
            .background(c.surface, Shapes.card)
            .border(if (acked) 2.dp else 1.dp, if (acked) c.safe else c.outline, Shapes.card)
            .padding(Space.l)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (acked) {
            Text(stringResource(R.string.status_help_coming), style = Lifeline.type.headline, color = c.safe, modifier = Modifier.semantics { heading() })
            eta?.let { Text(stringResource(R.string.status_eta, it), style = Lifeline.type.display, color = c.ink) }
            ack?.note?.takeIf { it.isNotBlank() }?.let { com.lifeline.mesh.ui.components.TranslatableNote(it, language, auto = true) }
        } else {
            Text(stringResource(R.string.status_title), style = Lifeline.type.title, color = c.ink, modifier = Modifier.semantics { heading() })
        }
        if (what.isNotBlank()) Text(if (acked) stringResource(R.string.status_your_alert, what) else what, style = Lifeline.type.label, color = c.inkMuted)
        NearestTeam()

        val passed = alert.handedTo > 0
        StatusTimeline(
            listOf(
                TimelineStep(stringResource(R.string.status_created), StepState.Done),
                if (passed) TimelineStep(pluralStringResource(R.plurals.status_passed, alert.handedTo, alert.handedTo),
                    if (acked) StepState.Done else StepState.Current)
                else TimelineStep(stringResource(R.string.status_waiting), if (acked) StepState.Done else StepState.Current,
                    detail = stringResource(R.string.status_waiting_detail)),
                TimelineStep(
                    stringResource(R.string.status_help_coming),
                    // Only a real reply makes this step active: never suggest help is coming before it is.
                    if (acked) StepState.Done else StepState.Pending,
                    detail = eta?.let { stringResource(R.string.status_eta, it) },
                    tone = if (acked) c.safe else null,
                ),
            )
        )
        SecondaryButton(stringResource(R.string.status_send_another), onSendAnother, Modifier.fillMaxWidth())
    }
}

/**
 * The nearest rescue team I can see on the mesh (Phase 10), with its live distance and a travel-time estimate at that
 * team's usual speed. Shown only when both positions are known; the estimate is computed in Kotlin and says so.
 */
@Composable
private fun NearestTeam() {
    val repo = (LocalContext.current.applicationContext as com.lifeline.mesh.LifelineApp).repository
    val positions by repo.positions.collectAsStateWithLifecycle()
    val me by repo.myFix.collectAsStateWithLifecycle()
    val fix = me ?: return
    val now = System.currentTimeMillis()
    val nearest = positions.values
        .filter { it.role.isTeam && now - it.heardAt < com.lifeline.mesh.mesh.PositionBeacon.SHOW_MS }
        .map { it to com.lifeline.mesh.triage.GeoMath.distanceM(fix.lat, fix.lon, it.lat, it.lon) }
        .minByOrNull { it.second } ?: return
    val (team, d) = nearest
    val minutes = com.lifeline.mesh.triage.Teams.etaMinutes(d, com.lifeline.mesh.triage.Teams.defaultTravel(team.role))
    val who = stringResource(com.lifeline.mesh.ui.onboarding.teamLabel(team.role.code)) + if (team.name.isNotBlank()) " (${team.name})" else ""
    Text(
        stringResource(R.string.status_nearest, who, com.lifeline.mesh.ui.rescuer.distanceText(d), minutes),
        style = Lifeline.type.body, color = Lifeline.colors.link,
    )
}
