package com.lifeline.mesh.ui.rescuer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.packet.PacketCodec
import com.lifeline.mesh.triage.GeoMath
import com.lifeline.mesh.triage.RankedAlert
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.components.SeverityBadge
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.Touch

private val ETAS = listOf(10, 20, 30, 60)

/**
 * Alert detail (DESIGN.md 8.4): every field, every merged report, ETA picker (10, 20, 30, 60 min), an optional short
 * note (40 bytes, the Ack limit), and "Send help is coming", which acks every report in the group.
 * The mini map arrives with the Map phase; translate with Phase 8.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailSheet(r: RankedAlert, now: Long, onSend: (eta: Int, note: String) -> Unit, onDismiss: () -> Unit, language: String = "en") {
    val c = Lifeline.colors
    val a = r.alert
    val context = LocalContext.current
    val estimate = myEstimate(r)
    val choices = remember(estimate) { (listOfNotNull(estimate?.minutes?.coerceIn(1, 255)) + ETAS).distinct().sorted().take(5) }
    var eta by rememberSaveable { mutableIntStateOf(a.ackEta ?: estimate?.minutes?.coerceIn(1, 255) ?: 20) }
    var note by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = Shapes.sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).imePadding(),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(stringResource(R.string.alert_rank, r.rank), style = Lifeline.type.label, color = c.inkMuted)
                SeverityBadge(a.severity)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                NeedIcons(a)
                Text(needsText(a), style = Lifeline.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })
            }
            Text(peopleText(a), style = Lifeline.type.bodyStrong, color = c.ink)

            val p = a.position
            Text(
                when {
                    p == null -> stringResource(R.string.alert_location_unknown)
                    p.accuracyM != null -> stringResource(R.string.detail_position, p.lat!!, p.lon!!, p.accuracyM)
                    else -> stringResource(R.string.detail_position_noacc, p.lat!!, p.lon!!)
                } + (r.distanceM?.let { " · " + distanceText(it) + " " + GeoMath.compass(r.bearingDeg!!) } ?: ""),
                style = Lifeline.type.body, color = c.ink,
            )
            Text(stringResource(R.string.detail_first, ago(context, now, a.firstAt)), style = Lifeline.type.caption, color = c.inkMuted)

            Text(stringResource(R.string.detail_reports) + " · ${a.count}", style = Lifeline.type.title, color = c.ink,
                modifier = Modifier.padding(top = Space.s).semantics { heading() })
            a.reports.sortedByDescending { it.receivedAt }.forEach { rep ->
                Column(Modifier.fillMaxWidth().background(c.surfaceRaised, Shapes.card).padding(Space.m)) {
                    Text(stringResource(R.string.detail_report_row, hex4(rep.origin), rep.hops, ago(context, now, rep.receivedAt)),
                        style = Lifeline.type.label, color = c.ink)
                    if (rep.note.isNotBlank()) com.lifeline.mesh.ui.components.TranslatableNote(rep.note, language)
                }
            }

            if (a.acked) Text(stringResource(R.string.alert_eta_sent, a.ackEta ?: 0), style = Lifeline.type.bodyStrong, color = c.safe)

            Text(stringResource(R.string.detail_eta), style = Lifeline.type.label, color = c.inkMuted, modifier = Modifier.padding(top = Space.s))
            estimate?.let { e ->
                val t = e.travel
                Text(if (e.byRoad) stringResource(R.string.eta_basis_road, e.minutes, "%.1f".format(e.meters / 1000))
                    else stringResource(R.string.eta_basis, e.minutes, t.label, t.detour.toString(), t.kmh.toInt()),
                    style = Lifeline.type.caption, color = c.inkMuted)
                if (e.avoided > 0) Text(pluralStringResource(R.plurals.eta_avoided, e.avoided, e.avoided),
                    style = Lifeline.type.caption, color = c.warn)
                if (e.jobsFirst > 0) Text(pluralStringResource(R.plurals.eta_jobs_first, e.jobsFirst, e.jobsFirst),
                    style = Lifeline.type.caption, color = c.inkMuted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                choices.forEach { m ->
                    val on = m == eta
                    Box(
                        Modifier.weight(1f)
                            .heightIn(min = Touch.emergency)
                            .background(if (on) c.surfaceRaised else c.surface, Shapes.chip)
                            .border(if (on) 2.dp else 1.dp, if (on) c.link else c.outline, Shapes.chip)
                            .clickable(role = Role.RadioButton) { eta = m }
                            .semantics { selected = on },
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.detail_eta_option, m), style = Lifeline.type.bodyStrong, color = if (on) c.ink else c.inkMuted) }
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = PacketCodec.trimToBytes(it, PacketCodec.MAX_ACK_NOTE) },
                placeholder = { Text(stringResource(R.string.detail_note_hint), style = Lifeline.type.body) },
                singleLine = true,
                textStyle = Lifeline.type.body,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.detail_sent_to, minOf(a.count, 5)), style = Lifeline.type.caption, color = c.inkMuted)
            PrimaryButton(
                stringResource(if (a.acked) R.string.detail_send_again else R.string.detail_send),
                { onSend(eta, note) },
                Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.xl))
        }
    }
}
