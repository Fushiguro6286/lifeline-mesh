package com.lifeline.mesh.ui.rescuer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.R
import com.lifeline.mesh.ai.LocationChoice
import com.lifeline.mesh.location.LocationProvider
import com.lifeline.mesh.packet.Geo
import com.lifeline.mesh.packet.Packet
import com.lifeline.mesh.packet.PacketCodec
import com.lifeline.mesh.packet.ReportType
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.onboarding.OptionCard
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Community report (packet type 4): road blocked, water available, shelter open or full, with an optional note and
 * this phone's position. Relayed like any other packet, so every phone's Rescue tab and map can show it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(onDismiss: () -> Unit) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val repo = (context.applicationContext as LifelineApp).repository
    val location = remember { LocationProvider(context) }
    val scope = rememberCoroutineScope()
    var type by rememberSaveable { mutableStateOf<ReportType?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var loc by remember { mutableStateOf<LocationChoice?>(null) }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { loc = location.best(repo.myFix.value) }

    val sentNote = PacketCodec.trimToBytes(note.trim(), PacketCodec.MAX_REPORT_NOTE)
    fun send(t: ReportType) {
        if (sending) return
        sending = true
        scope.launch {
            val fix = (loc ?: location.best(repo.myFix.value)).fix
            repo.originate { h ->
                Packet.Report(h, fix?.let { Geo.toE6(it.lat) } ?: 0, fix?.let { Geo.toE6(it.lon) } ?: 0,
                    fix?.let { Geo.accuracyStep(it.accuracyM) } ?: Geo.ACCURACY_UNKNOWN, t, sentNote)
            }
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = Shapes.sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(bottom = Space.xl).imePadding(),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(stringResource(R.string.report_sheet_title), style = Lifeline.type.title, color = c.ink)
            Text(stringResource(R.string.report_sheet_body), style = Lifeline.type.body, color = c.inkMuted)
            listOf(
                ReportType.ROAD_BLOCKED to (R.string.report_1 to R.drawable.ic_block),
                ReportType.WATER_AVAILABLE to (R.string.report_2 to R.drawable.ic_water_drop),
                ReportType.SHELTER_OPEN to (R.string.report_3 to R.drawable.ic_groups),
                ReportType.SHELTER_FULL to (R.string.report_4 to R.drawable.ic_groups),
            ).forEach { (t, res) ->
                OptionCard(stringResource(res.first), type == t, { type = t }, icon = res.second)
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                label = { Text(stringResource(R.string.report_note)) },
                placeholder = { Text(stringResource(R.string.report_note_hint)) },
                textStyle = Lifeline.type.body,
                modifier = Modifier.fillMaxWidth(),
            )
            if (sentNote != note.trim()) Text(stringResource(R.string.review_note_trimmed, sentNote), style = Lifeline.type.caption, color = c.inkMuted)
            val l = loc
            Text(
                when {
                    l == null -> stringResource(R.string.report_loc_finding)
                    l.fix == null -> stringResource(R.string.report_loc_none)
                    l.approximate -> stringResource(R.string.report_loc_approx)
                    else -> stringResource(R.string.report_loc_exact, l.fix.accuracyM?.toInt() ?: 0)
                },
                style = Lifeline.type.caption, color = if (l != null && l.fix == null) c.warn else c.inkMuted,
            )
            Spacer(Modifier.size(Space.xs))
            PrimaryButton(
                stringResource(if (sending) R.string.report_sending else R.string.report_send),
                { type?.let(::send) },
                Modifier.fillMaxWidth(),
                enabled = type != null && !sending,
            )
        }
    }
}
