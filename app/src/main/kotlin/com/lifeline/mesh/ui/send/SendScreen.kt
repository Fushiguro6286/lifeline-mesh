package com.lifeline.mesh.ui.send

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.lifeline.mesh.ai.scriptLanguage
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
    private val ai = (app as LifelineApp).ai
    private val location = LocationProvider(app)
    /** App language for gate questions (the sender's language). */
    private var language = "en"
    private val asked = HashSet<QualityGate.Missing>()

    sealed interface Loc {
        data object Finding : Loc
        data class Ready(val choice: LocationChoice) : Loc
    }

    /** [severityChosen]: the user picked a severity, so edits to needs/people no longer change it. */
    /**
     * [aiPending]: the on-device model is still reading the message. [edited]: the user changed a field, so a late AI
     * answer must not overwrite it. [questions]: model-written gate questions by missing field.
     */
    data class Review(
        val transcript: String,
        val draft: AlertDraft,
        val loc: Loc,
        val sending: Boolean = false,
        val severityChosen: Boolean = false,
        val aiPending: Boolean = false,
        val edited: Boolean = false,
        val questions: Map<QualityGate.Missing, String> = emptyMap(),
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

    /**
     * Rules run instantly on the text; GPS starts at the same time (at most 8 s). If the on-device model passed this
     * language's probe, it reads the message too (6 s GPU / 10 s CPU timeout) and its answer replaces the rules draft
     * only if it is valid and the user has not edited anything yet (CLAUDE.md 9.1).
     */
    fun startReview(text: String, appLanguage: String) {
        language = appLanguage
        asked.clear()
        val useAi = ai.canUse(scriptLanguage(text))
        _review.value = Review(text, RuleExtractor.extract(text), Loc.Finding, aiPending = useAi)
        if (useAi) viewModelScope.launch {
            val d = ai.extract(text)
            _review.update { r ->
                if (r == null || r.transcript != text) r
                else if (d != null && !r.edited) r.copy(draft = d, aiPending = false)
                else r.copy(aiPending = false)
            }
            askIfBlocked()
        } else askIfBlocked()
        locJob.cancel()
        val job = viewModelScope.async { location.best() }
        locJob = job
        viewModelScope.launch {
            val c = runCatching { job.await() }.getOrNull() ?: return@launch
            _review.update { it?.copy(loc = Loc.Ready(c)) }
        }
    }

    fun edit(change: (AlertDraft) -> AlertDraft) {
        _review.update { r ->
            r ?: return@update null
            val d = change(r.draft)
            if (r.severityChosen) r.copy(draft = d, edited = true)
            else r.copy(draft = d.copy(severity = RuleExtractor.severityFor(r.transcript, d.needs, d.people)), edited = true)
        }
        askIfBlocked()
    }

    fun chooseSeverity(level: Int) = _review.update { it?.copy(draft = it.draft.copy(severity = level), severityChosen = true, edited = true) }

    /** One model-written question per missing field, in the app language (4 s timeout); else the fixed string shows. */
    private fun askIfBlocked() {
        val r = _review.value ?: return
        if (r.aiPending) return
        val missing = QualityGate.check(r.draft).ask ?: return
        if (!asked.add(missing) || !ai.canUse(language)) return
        viewModelScope.launch {
            val q = ai.question(language, missing) ?: return@launch
            _review.update { it?.copy(questions = it.questions + (missing to q)) }
        }
    }

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

    /** Show the delivery status of an alert sent elsewhere on this screen (the ambulance request). */
    fun showStatus(id: Long) { _statusFor.value = id }

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
fun SendScreen(settings: Settings, onFirstAid: () -> Unit = {}, vm: SendViewModel = viewModel()) {
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
    var speaking by rememberSaveable { mutableStateOf(false) }
    var typedStart by rememberSaveable { mutableStateOf("") }
    var ambulance by rememberSaveable { mutableStateOf(false) }

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
            BigIconButton(R.drawable.ic_mic, stringResource(R.string.speak), { speaking = true }, Modifier.weight(1f))
            BigIconButton(R.drawable.ic_keyboard, stringResource(R.string.type), { typedStart = ""; typing = true }, Modifier.weight(1f))
        }

        AmbulanceCard({ ambulance = true })
        CheckInCard(sentAt = checkInAt, onSafe = { vm.checkIn(settings.displayName) })
        FirstAidLink(onFirstAid)
        Spacer(Modifier.height(Space.l))
    }

    if (speaking) {
        SpeakSheet(
            language = settings.language.tag,
            onDone = { text -> speaking = false; vm.startReview(text, settings.language.tag) },
            onType = { partial -> speaking = false; typedStart = partial; typing = true },
            onDismiss = { speaking = false },
        )
    }
    if (typing) {
        TypeSheet(
            initial = typedStart,
            onDone = { text -> typing = false; vm.startReview(text, settings.language.tag) },
            onDismiss = { typing = false },
        )
    }
    review?.let { r -> ReviewSheet(r, settings, vm) }
    if (ambulance) AmbulanceSheet(onDismiss = { ambulance = false }, onSent = vm::showStatus)
}

/** Typed input, always available (and the fallback when voice is not). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSheet(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Lifeline.colors
    var text by rememberSaveable { mutableStateOf(initial) }
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

/**
 * Listening panel (DESIGN.md 8.2): live transcript and a level meter; Done (or a pause) ends it and opens the review.
 * Uses the on-device recognizer where the phone has one. Any problem says what happened and offers typing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakSheet(language: String, onDone: (String) -> Unit, onType: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Lifeline.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val speech = remember { com.lifeline.mesh.ai.SpeechInput(context) }
    var text by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(0f) }
    var listening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val doneNow by androidx.compose.runtime.rememberUpdatedState(onDone)

    fun listen() {
        error = null
        listening = true
        speech.start(language) { e ->
            when (e) {
                com.lifeline.mesh.ai.SpeechInput.Event.Ready -> listening = true
                is com.lifeline.mesh.ai.SpeechInput.Event.Partial -> text = e.text
                is com.lifeline.mesh.ai.SpeechInput.Event.Level -> level = e.rmsDb
                is com.lifeline.mesh.ai.SpeechInput.Event.Final -> { listening = false; text = e.text; doneNow(e.text) }
                is com.lifeline.mesh.ai.SpeechInput.Event.Failed -> {
                    listening = false
                    if (e.reason == com.lifeline.mesh.ai.SpeechInput.Reason.NO_MATCH && text.isNotBlank()) doneNow(text)
                    else error = when (e.reason) {
                        com.lifeline.mesh.ai.SpeechInput.Reason.NO_MATCH -> R.string.speak_err_nomatch
                        com.lifeline.mesh.ai.SpeechInput.Reason.LANGUAGE_UNAVAILABLE -> R.string.speak_err_language
                        com.lifeline.mesh.ai.SpeechInput.Reason.NO_PERMISSION -> R.string.speak_err_permission
                        else -> R.string.speak_err_other
                    }
                }
            }
        }
    }

    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) listen() else error = R.string.speak_err_permission }

    LaunchedEffect(Unit) {
        when {
            !speech.available() -> error = R.string.speak_err_unavailable
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED -> listen()
            else -> permission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { speech.destroy() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = Shapes.sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Text(stringResource(R.string.speak), style = Lifeline.type.headline, color = c.ink)
            val status = error?.let { stringResource(it) } ?: if (listening) stringResource(R.string.speak_listening) else ""
            if (status.isNotEmpty()) Text(status, style = Lifeline.type.bodyStrong, color = if (error != null) c.warn else c.link)
            Text(
                text.ifBlank { stringResource(R.string.speak_hint) },
                style = Lifeline.type.title, color = if (text.isBlank()) c.inkMuted else c.ink,
                modifier = Modifier.fillMaxWidth().padding(vertical = Space.s),
            )
            LevelMeter(level, listening)
            if (error == R.string.speak_err_language && speech.canDownloadPack()) {
                SecondaryButton(stringResource(R.string.speak_get_pack), { speech.downloadPack(language) }, Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                SecondaryButton(stringResource(R.string.speak_type_instead), { speech.destroy(); onType(text) }, Modifier.weight(1f))
                if (error != null && error != R.string.speak_err_unavailable && error != R.string.speak_err_language) {
                    PrimaryButton(stringResource(R.string.speak_try_again), { text = ""; listen() }, Modifier.weight(1f))
                } else {
                    PrimaryButton(
                        stringResource(R.string.speak_done),
                        { if (listening) speech.stop() else if (text.isNotBlank()) onDone(text) },
                        Modifier.weight(1f),
                        enabled = listening || text.isNotBlank(),
                    )
                }
            }
        }
    }
}

/** Twelve bars that follow the microphone level; static with reduced motion. */
@Composable
private fun LevelMeter(rmsDb: Float, active: Boolean) {
    val c = Lifeline.colors
    val reduced = Lifeline.reducedMotion
    val norm = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    val animated by androidx.compose.animation.core.animateFloatAsState(if (active) norm else 0f, label = "level")
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until 12) {
            val shape = 1f - kotlin.math.abs(i - 5.5f) / 7f
            val h = if (reduced) (if (active) 0.5f else 0.1f) else (0.12f + animated * shape).coerceIn(0.1f, 1f)
            androidx.compose.foundation.layout.Box(
                Modifier.weight(1f).fillMaxHeight(h)
                    .background(if (active) c.link else c.outline, Shapes.chip)
            )
        }
    }
}

/** Quiet entry to the vetted first-aid guides, under the check-in card. */
@Composable
private fun FirstAidLink(onClick: () -> Unit) {
    val c = Lifeline.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = com.lifeline.mesh.ui.theme.Touch.standard).background(c.surface, Shapes.card)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(Space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_local_hospital), null, tint = c.link,
            modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.firstaid_title), style = Lifeline.type.bodyStrong, color = c.ink)
            Text(stringResource(R.string.firstaid_open_body), style = Lifeline.type.caption, color = c.inkMuted)
        }
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_chevron_right), null, tint = c.inkMuted)
    }
}
