package com.lifeline.mesh.ui.send

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.R
import com.lifeline.mesh.ai.AlertDraft
import com.lifeline.mesh.ai.LocationChoice
import com.lifeline.mesh.ai.QualityGate
import com.lifeline.mesh.ai.RuleExtractor
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.location.LocationProvider
import com.lifeline.mesh.packet.Geo
import com.lifeline.mesh.packet.Header
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.Packet
import com.lifeline.mesh.packet.PacketType
import com.lifeline.mesh.ui.components.BigIconButton
import com.lifeline.mesh.ui.components.HoldToSendButton
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The send flow's state: the review being edited, and which alert's delivery status to show. */
class SendViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as LifelineApp).repository
    private val location = LocationProvider(app)

    sealed interface Loc {
        data object Finding : Loc
        data class Ready(val choice: LocationChoice) : Loc
    }

    /** [severityChosen]: the user picked a severity, so edits to needs/people no longer change it. */
    data class Review(
        val transcript: String,
        val draft: AlertDraft,
        val loc: Loc,
        val sending: Boolean = false,
        val severityChosen: Boolean = false,
    )

    private val _review = MutableStateFlow<Review?>(null)
    val review: StateFlow<Review?> = _review

    /** Alert whose delivery status replaces the SOS button. Null: show the button. */
    private val _statusFor = MutableStateFlow<Long?>(null)
    val statusFor: StateFlow<Long?> = _statusFor
    private val _dismissed = MutableStateFlow<Set<Long>>(emptySet())
    val dismissed: StateFlow<Set<Long>> = _dismissed

    private val _checkInAt = MutableStateFlow<Long?>(null)
    val checkInAt: StateFlow<Long?> = _checkInAt

    private var locJob: Deferred<LocationChoice> = CompletableDeferred(LocationChoice(null, true))

    /** Rules run instantly on the text; GPS starts at the same time (at most 8 s). */
    fun startReview(text: String) {
        _review.value = Review(text, RuleExtractor.extract(text), Loc.Finding)
        locJob.cancel()
        val job = viewModelScope.async { location.best() }
        locJob = job
        viewModelScope.launch {
            val c = runCatching { job.await() }.getOrNull() ?: return@launch
            _review.update { it?.copy(loc = Loc.Ready(c)) }
        }
    }

    fun edit(change: (AlertDraft) -> AlertDraft) = _review.update { r ->
        r ?: return@update null
        val d = change(r.draft)
        if (r.severityChosen) r.copy(draft = d)
        else r.copy(draft = d.copy(severity = RuleExtractor.severityFor(r.transcript, d.needs, d.people)))
    }

    fun chooseSeverity(level: Int) = _review.update { it?.copy(draft = it.draft.copy(severity = level), severityChosen = true) }

    fun closeReview() {
        _review.value = null
        locJob.cancel()
    }

    /** Sends once the gate passes, waiting for the location lookup if it is still running. */
    fun send() {
        val r = _review.value ?: return
        if (r.sending || !QualityGate.check(r.draft).ok) return
        _review.value = r.copy(sending = true)
        viewModelScope.launch {
            val choice = runCatching { locJob.await() }.getOrDefault(LocationChoice(null, true))
            val p = repo.originate { h -> alertPacket(h, _review.value?.draft ?: r.draft, choice) }
            _statusFor.value = p.header.messageId
            _review.value = null
        }
    }

    /** Panic SOS: no speech, no gate. Last known position at once; a fresh fix only if there is none. */
    fun sendPanic() {
        viewModelScope.launch {
            val choice = location.lastKnown()?.let { LocationChoice(it, approximate = true) } ?: location.best()
            val p = repo.originate { h -> alertPacket(h, QualityGate.panicDraft(), choice) }
            _statusFor.value = p.header.messageId
        }
    }

    fun checkIn(name: String) {
        viewModelScope.launch {
            val fix = location.lastKnown() ?: location.fresh()
            repo.originate { h ->
                Packet.CheckIn(h, fix?.let { Geo.toE6(it.lat) } ?: 0, fix?.let { Geo.toE6(it.lon) } ?: 0,
                    fix?.let { Geo.accuracyStep(it.accuracyM) } ?: Geo.ACCURACY_UNKNOWN, name)
            }
            _checkInAt.value = System.currentTimeMillis()
        }
    }

    fun dismissStatus(id: Long) {
        _dismissed.update { it + id }
        _statusFor.value = null
    }

    companion object {
        fun alertPacket(h: Header, d: AlertDraft, loc: LocationChoice) = Packet.Alert(
            header = h,
            latE6 = loc.fix?.let { Geo.toE6(it.lat) } ?: 0,
            lonE6 = loc.fix?.let { Geo.toE6(it.lon) } ?: 0,
            accuracy = loc.fix?.let { Geo.accuracyStep(it.accuracyM) } ?: Geo.ACCURACY_UNKNOWN,
            needs = Need.toMask(d.needs),
            severity = d.severity.coerceIn(0, 3),
            people = d.people.coerceIn(0, 255),
            note = d.note,
        )
    }
}

private const val STATUS_KEEP_MS = 2 * 60 * 60 * 1000L

/**
 * SOS tab (DESIGN.md 8.2): greeting, the hold-to-send button (or the delivery status of the last alert),
 * Speak and Type, and the "I'm safe" card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(settings: Settings, vm: SendViewModel = viewModel()) {
    val c = Lifeline.colors
    val repo = (androidx.compose.ui.platform.LocalContext.current.applicationContext as LifelineApp).repository
    val packets by repo.packets.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val statusFor by vm.statusFor.collectAsStateWithLifecycle()
    val dismissed by vm.dismissed.collectAsStateWithLifecycle()
    val checkInAt by vm.checkInAt.collectAsStateWithLifecycle()

    // After a restart the last alert's status comes back (it is in Room) unless the user dismissed it.
    val latestMine = packets.firstOrNull { it.isMine && it.type == PacketType.ALERT.code }
    val statusAlert = packets.firstOrNull { it.id == statusFor }
        ?: latestMine?.takeIf { it.id !in dismissed && System.currentTimeMillis() - it.receivedAt < STATUS_KEEP_MS }
    val ack = statusAlert?.let { a -> packets.firstOrNull { it.type == PacketType.ACK.code && it.refId == a.id } }

    var typing by rememberSaveable { mutableStateOf(false) }
    var speakNote by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Text(stringResource(R.string.sos_greeting), style = Lifeline.type.title, color = c.ink, textAlign = TextAlign.Center)

        if (statusAlert != null) {
            DeliveryStatusCard(statusAlert, ack, onSendAnother = { vm.dismissStatus(statusAlert.id) })
        } else {
            // Room around the 200 dp button for its signal rings.
            Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                HoldToSendButton(onSend = vm::sendPanic)
            }
        }

        Text(stringResource(R.string.sos_or), style = Lifeline.type.label, color = c.inkMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            BigIconButton(R.drawable.ic_mic, stringResource(R.string.speak), { speakNote = true; typing = true }, Modifier.weight(1f))
            BigIconButton(R.drawable.ic_keyboard, stringResource(R.string.type), { speakNote = false; typing = true }, Modifier.weight(1f))
        }

        CheckInCard(sentAt = checkInAt, onSafe = { vm.checkIn(settings.displayName) })
        Spacer(Modifier.height(Space.l))
    }

    if (typing) {
        TypeSheet(
            speakNote = speakNote,
            onDone = { text -> typing = false; vm.startReview(text) },
            onDismiss = { typing = false },
        )
    }
    review?.let { r -> ReviewSheet(r, settings, vm) }
}

/** Typed input. Until speech arrives (Phase 7), Speak opens this too, saying so honestly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSheet(speakNote: Boolean, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Lifeline.colors
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = Shapes.sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(bottom = Space.xl).imePadding(),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(stringResource(R.string.sos_type_title), style = Lifeline.type.title, color = c.ink)
            if (speakNote) Text(stringResource(R.string.sos_speak_later), style = Lifeline.type.body, color = c.warn)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(500) },
                placeholder = { Text(stringResource(R.string.sos_type_hint), style = Lifeline.type.body) },
                textStyle = Lifeline.type.body,
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                SecondaryButton(stringResource(R.string.sos_cancel), onDismiss, Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.sos_continue), { onDone(text.trim()) }, Modifier.weight(2f), enabled = text.isNotBlank())
            }
            Spacer(Modifier.size(Space.s))
        }
    }
}
