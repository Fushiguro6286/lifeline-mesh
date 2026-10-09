package com.lifeline.mesh.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.ai.DraftSource
import com.lifeline.mesh.ai.QualityGate
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.packet.Header
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.PacketCodec
import com.lifeline.mesh.ui.components.Haptics
import com.lifeline.mesh.ui.components.NeedChip
import com.lifeline.mesh.ui.components.PacketBytesStrip
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.components.sampleAlertFields
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.Touch
import com.lifeline.mesh.ui.theme.severity
import kotlinx.coroutines.launch

/**
 * Review sheet (DESIGN.md 8.2): the words, who understood them, editable needs / people / severity, location,
 * the quality-gate question when something is missing, the exact packet bytes, and "Send alert".
 * The Send button always looks tappable; while something is missing it scrolls to the question instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(r: SendViewModel.Review, settings: Settings, vm: SendViewModel) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = QualityGate.check(r.draft)
    val questionView = remember { BringIntoViewRequester() }

    // The exact bytes this alert will be (only the random message ID and time differ when sent).
    val choice = (r.loc as? SendViewModel.Loc.Ready)?.choice ?: com.lifeline.mesh.ai.LocationChoice(null, true)
    val preview = PacketCodec.encode(
        SendViewModel.alertPacket(Header(0x0123456789ABCDEFL, settings.originId, PacketCodec.TTL_DEFAULT, 0, System.currentTimeMillis() / 1000), r.draft, choice)
    )
    val sentNote = PacketCodec.trimToBytes(r.draft.note, PacketCodec.MAX_ALERT_NOTE)

    ModalBottomSheet(
        onDismissRequest = vm::closeReview,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = Shapes.sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f).verticalScroll(rememberScrollState()).padding(horizontal = Space.screen),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Text(stringResource(R.string.review_title), style = Lifeline.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })

            // The words, with who understood them.
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.link))
                    Text("“${r.transcript}”", style = Lifeline.type.body, color = c.ink, modifier = Modifier.padding(start = Space.m))
                }
                Text(
                    stringResource(if (r.draft.source == DraftSource.AI) R.string.review_source_ai else R.string.review_source_rules),
                    style = Lifeline.type.caption, color = c.inkMuted,
                    modifier = Modifier.border(1.dp, c.outline, Shapes.chip).padding(horizontal = Space.s, vertical = 2.dp),
                )
                if (sentNote != r.draft.note) {
                    Text(stringResource(R.string.review_note_trimmed, sentNote), style = Lifeline.type.caption, color = c.inkMuted)
                }
            }

            // Quality gate: one calm question for the first missing field. The card stays in place when everything
            // is filled in (it turns into "Ready to send"), so nothing below jumps under the user's finger.
            val missing = gate.ask
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .bringIntoViewRequester(questionView)
                    .background(c.surfaceRaised, Shapes.card)
                    .border(2.dp, if (missing == null) c.safe else c.warn, Shapes.card)
                    .padding(Space.l)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(if (missing == null) R.drawable.ic_check_circle else R.drawable.ic_info), null,
                    tint = if (missing == null) c.safe else c.warn, modifier = Modifier.size(24.dp),
                )
                Text(
                    stringResource(
                        when (missing) {
                            QualityGate.Missing.NEEDS -> R.string.gate_q_needs
                            QualityGate.Missing.PEOPLE -> R.string.gate_q_people
                            null -> R.string.gate_ready
                        }
                    ),
                    style = Lifeline.type.bodyStrong, color = c.ink,
                )
            }

            Section(stringResource(R.string.review_needs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Need.selectable.forEach { need ->
                    NeedChip(need, selected = need in r.draft.needs, onToggle = { on ->
                        vm.edit { d -> d.copy(needs = if (on) d.needs + need else d.needs - need) }
                    })
                }
            }

            Section(stringResource(R.string.review_people))
            PeopleStepper(r.draft.people) { n -> vm.edit { it.copy(people = n) } }

            Section(stringResource(R.string.review_severity))
            SeverityPicker(r.draft.severity, vm::chooseSeverity)

            LocationRow(r.loc)

            PacketBytesStrip(preview, sampleAlertFields(noteBytes = preview.size - 31))

            PrimaryButton(
                text = stringResource(if (r.sending) R.string.review_sending else R.string.review_send),
                onClick = {
                    if (gate.ok) vm.send() else {
                        Haptics.tick(context)
                        scope.launch { questionView.bringIntoView() }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                color = if (gate.ok) c.sos else c.sos.copy(alpha = 0.45f),
                enabled = !r.sending,
            )
            Spacer(Modifier.height(Space.xl))
        }
    }
}

@Composable
private fun Section(text: String) =
    Text(text, style = Lifeline.type.label, color = Lifeline.colors.inkMuted, modifier = Modifier.semantics { heading() })

/** Big − and + with the number in Display style (DESIGN.md 8.2). */
@Composable
private fun PeopleStepper(people: Int, onChange: (Int) -> Unit) {
    val c = Lifeline.colors
    val less = stringResource(R.string.review_people_less)
    val more = stringResource(R.string.review_people_more)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        StepButton(R.drawable.ic_remove, less, enabled = people > 0) { onChange((people - 1).coerceAtLeast(0)) }
        Text(
            people.toString(), style = Lifeline.type.display, color = if (people == 0) c.warn else c.ink,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        StepButton(R.drawable.ic_add, more, enabled = people < 255) { onChange((people + 1).coerceAtMost(255)) }
    }
}

@Composable
private fun StepButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Lifeline.colors
    Box(
        Modifier
            .size(Touch.emergency)
            .background(c.surfaceRaised, Shapes.circle)
            .border(1.dp, c.outline, Shapes.circle)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), null, tint = if (enabled) c.ink else c.inkMuted) }
}

/** Four-way segmented control; the selected segment takes its severity color and always shows the word. */
@Composable
private fun SeverityPicker(level: Int, onChange: (Int) -> Unit) {
    val c = Lifeline.colors
    val names = listOf(R.string.severity_0, R.string.severity_1, R.string.severity_2, R.string.severity_3)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        names.forEachIndexed { i, name ->
            val on = i == level
            val tone = c.severity(i)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = Touch.standard)
                    .background(if (on) c.surfaceRaised else c.surface, Shapes.chip)
                    .border(if (on) 2.dp else 1.dp, if (on) tone else c.outline, Shapes.chip)
                    .clickable(role = Role.RadioButton) { onChange(i) }
                    .semantics { selected = on }
                    .padding(horizontal = Space.xs, vertical = Space.s),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(name), style = Lifeline.type.label, color = if (on) c.ink else c.inkMuted, maxLines = 1) }
        }
    }
}

@Composable
private fun LocationRow(loc: SendViewModel.Loc) {
    val c = Lifeline.colors
    val (text, tone) = when (loc) {
        SendViewModel.Loc.Finding -> stringResource(R.string.review_location_finding) to c.inkMuted
        is SendViewModel.Loc.Ready -> {
            val fix = loc.choice.fix
            when {
                fix == null -> stringResource(R.string.review_location_unknown) to c.warn
                loc.choice.approximate -> stringResource(R.string.review_location_approx) to c.warn
                fix.accuracyM != null -> stringResource(R.string.review_location_exact, fix.accuracyM.toInt().coerceAtLeast(1)) to c.safe
                else -> stringResource(R.string.review_location_exact_unknown) to c.safe
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Icon(painterResource(R.drawable.ic_location_on), null, tint = tone, modifier = Modifier.size(24.dp))
        Text(text, style = Lifeline.type.body, color = c.ink)
    }
}
