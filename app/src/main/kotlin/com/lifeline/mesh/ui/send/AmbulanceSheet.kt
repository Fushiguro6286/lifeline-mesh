package com.lifeline.mesh.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.R
import com.lifeline.mesh.location.LocationProvider
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.PacketCodec
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.Touch
import kotlinx.coroutines.launch

/** Red-outlined entry on the SOS tab and the First aid screen (Phase 10). */
@Composable
fun AmbulanceCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Lifeline.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = Touch.emergency).background(c.surface, Shapes.card).border(2.dp, c.sos, Shapes.card)
            .clickable(role = Role.Button, onClick = onClick).padding(Space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(painterResource(R.drawable.ic_emergency), null, tint = c.sos, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.amb_card_title), style = Lifeline.type.bodyStrong, color = c.ink)
            Text(stringResource(R.string.amb_card_body), style = Lifeline.type.caption, color = c.inkMuted)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = c.inkMuted)
    }
}

/**
 * Urgent ambulance request (Phase 10): an Alert with needs medical + ambulance, severity 3, the number of patients and
 * a short note, from this phone's position. Ambulance-team phones sound an alarm for it; it shows on the SOS tab's
 * delivery status like any alert. No gate: a helper asking for an ambulance has already decided.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmbulanceSheet(onDismiss: () -> Unit, onSent: (Long) -> Unit = {}) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val repo = (context.applicationContext as LifelineApp).repository
    val location = remember { LocationProvider(context) }
    val scope = rememberCoroutineScope()
    var people by rememberSaveable { mutableIntStateOf(1) }
    var note by rememberSaveable { mutableStateOf("") }
    var loc by remember { mutableStateOf<SendViewModel.Loc>(SendViewModel.Loc.Finding) }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { loc = SendViewModel.Loc.Ready(location.best()) }
    val sentNote = PacketCodec.trimToBytes(note.trim(), PacketCodec.MAX_ALERT_NOTE)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = Shapes.sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(bottom = Space.xl).imePadding(),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(stringResource(R.string.amb_title), style = Lifeline.type.headline, color = c.ink)
            Text(stringResource(R.string.amb_card_body), style = Lifeline.type.body, color = c.inkMuted)
            Text(stringResource(R.string.amb_people), style = Lifeline.type.label, color = c.inkMuted)
            PeopleStepper(people) { people = it.coerceIn(1, 255) }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                placeholder = { Text(stringResource(R.string.amb_note_hint), style = Lifeline.type.body) },
                textStyle = Lifeline.type.body,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            if (sentNote != note.trim()) Text(stringResource(R.string.review_note_trimmed, sentNote), style = Lifeline.type.caption, color = c.inkMuted)
            LocationRow(loc)
            Spacer(Modifier.size(Space.xs))
            PrimaryButton(
                stringResource(if (sending) R.string.amb_sending else R.string.amb_send),
                {
                    if (!sending) {
                        sending = true
                        scope.launch {
                            val choice = (loc as? SendViewModel.Loc.Ready)?.choice ?: location.best()
                            val draft = com.lifeline.mesh.ai.AlertDraft(setOf(Need.MEDICAL, Need.AMBULANCE), people, 3, sentNote)
                            val p = repo.originate { h -> SendViewModel.alertPacket(h, draft, choice) }
                            com.lifeline.mesh.ui.components.Haptics.strong(context)
                            onSent(p.header.messageId)
                            onDismiss()
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                color = c.sos,
                enabled = !sending,
            )
        }
    }
}
