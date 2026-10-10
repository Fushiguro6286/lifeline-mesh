package com.lifeline.mesh.ui.rescuer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.triage.GeoMath
import com.lifeline.mesh.triage.MergedAlert
import com.lifeline.mesh.triage.RankedAlert
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.components.SeverityBadge
import com.lifeline.mesh.ui.components.iconRes
import com.lifeline.mesh.ui.components.labelRes
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space

/**
 * One merged alert in the ranked list (DESIGN.md 8.4): rank in Display style, need icons, severity, people, distance and
 * direction, reports merged, time, note, and Acknowledge. Acknowledged cards dim and show the ETA that was sent.
 */
@Composable
fun AlertCard(r: RankedAlert, now: Long, onOpen: () -> Unit, modifier: Modifier = Modifier, language: String = "en") {
    val c = Lifeline.colors
    val a = r.alert
    val context = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (a.acked) 0.6f else 1f)
            .background(c.surface, Shapes.card)
            .border(1.dp, if (a.severity >= 3 && !a.acked) c.sos.copy(alpha = 0.6f) else c.outline, Shapes.card)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(Space.l),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(r.rank.toString(), style = Lifeline.type.display, color = c.ink, modifier = Modifier.widthIn(min = 36.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                NeedIcons(a)
                Spacer(Modifier.weight(1f))
                SeverityBadge(a.severity)
            }
            Text(needsText(a), style = Lifeline.type.bodyStrong, color = c.ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(peopleText(a) + " · " + (r.distanceM?.let { distanceText(it) + " " + GeoMath.compass(r.bearingDeg!!) }
                    ?: if (a.position == null) stringResource(R.string.alert_location_unknown) else ""),
                    style = Lifeline.type.body, color = c.ink)
                r.bearingDeg?.let {
                    Icon(painterResource(R.drawable.ic_navigation), null, tint = c.link, modifier = Modifier.size(18.dp).rotate(it.toFloat()))
                }
            }
            EtaLine(r)
            Text(
                (if (a.count > 1) stringResource(R.string.alert_merged, a.count) else stringResource(R.string.alert_single)) +
                    " · " + ago(context, now, a.lastAt) + if (a.viaInternet) " · via internet" else "",
                style = Lifeline.type.caption, color = c.inkMuted,
            )
            if (a.note.isNotBlank()) com.lifeline.mesh.ui.components.TranslatableNote(a.note, language)
            if (a.acked) {
                Text(stringResource(R.string.alert_eta_sent, a.ackEta ?: 0), style = Lifeline.type.bodyStrong, color = c.safe)
            } else {
                PrimaryButton(stringResource(R.string.alert_acknowledge), onOpen, Modifier.padding(top = Space.xs))
            }
        }
    }
}

@Composable
fun NeedIcons(a: MergedAlert) {
    val c = Lifeline.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (Need.PANIC in a.needs) Icon(painterResource(R.drawable.ic_sos), null, tint = c.sos, modifier = Modifier.size(22.dp))
        if (Need.AMBULANCE in a.needs) Icon(painterResource(R.drawable.ic_emergency), null, tint = c.sos, modifier = Modifier.size(22.dp))
        Need.selectable.filter { it in a.needs }.forEach {
            Icon(painterResource(it.iconRes()), null, tint = c.ink, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun needsText(a: MergedAlert): String {
    val res = LocalResources.current
    if (Need.PANIC in a.needs) return stringResource(R.string.alert_panic)
    val ambulance = if (Need.AMBULANCE in a.needs) listOf(res.getString(R.string.need_ambulance)) else emptyList()
    return (ambulance + Need.selectable.filter { it in a.needs }.map { res.getString(it.labelRes()) }).joinToString(" · ")
}

@Composable
fun peopleText(a: MergedAlert): String = when (a.people) {
    0 -> stringResource(R.string.alert_people_unknown)
    1 -> stringResource(R.string.alert_one_person)
    else -> stringResource(R.string.alert_people, a.people)
}

/** My estimate for this alert (Phase 10), or null when I need help myself or either position is unknown. */
@Composable
fun myEstimate(r: RankedAlert): Pair<Int, com.lifeline.mesh.triage.Travel>? {
    val repo = (LocalContext.current.applicationContext as com.lifeline.mesh.LifelineApp).repository
    val settings by repo.settings.collectAsStateWithLifecycle()
    val s = settings ?: return null
    if (s.role == com.lifeline.mesh.data.Role.NEEDS_HELP) return null
    val d = r.distanceM ?: return null
    return com.lifeline.mesh.triage.Teams.etaMinutes(d, s.travel) to s.travel
}

/** "About 7 min by road · estimate": straight-line distance × detour at an assumed speed, computed in Kotlin. */
@Composable
private fun EtaLine(r: RankedAlert) {
    val (min, travel) = myEstimate(r) ?: return
    Text(stringResource(R.string.eta_estimate, min, travel.label), style = Lifeline.type.body, color = Lifeline.colors.link)
}
