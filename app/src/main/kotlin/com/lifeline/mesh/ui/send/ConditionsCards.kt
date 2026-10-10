package com.lifeline.mesh.ui.send

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import com.lifeline.mesh.safety.WatchState
import com.lifeline.mesh.safety.WatchScanner
import com.lifeline.mesh.safety.SIMULATED_WATCH
import com.lifeline.mesh.safety.FoundWatch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.R
import com.lifeline.mesh.ai.WeatherLine
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.safety.VitalReading
import com.lifeline.mesh.safety.VitalsCheck
import com.lifeline.mesh.safety.WatchVitals
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun clock(ms: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms))

@Composable
private fun CardColumn(icon: Int, title: String, content: @Composable () -> Unit) {
    val c = Lifeline.colors
    Column(
        Modifier.fillMaxWidth().background(c.surface, Shapes.card).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Icon(painterResource(icon), null, tint = c.link, modifier = Modifier.size(24.dp))
            Text(title, style = Lifeline.type.title, color = c.ink)
        }
        content()
    }
}

/**
 * Live weather where I am (10 Oct 2026), from the rescue server (Open-Meteo for my ~11 km cell), when this phone is
 * online. The numbers come from the server; the on-device model may phrase them in one sentence ("Written on device",
 * every number checked, English only). Dangerous forecasts arrive as warnings (banner and alarm), not here.
 */
@Composable
fun WeatherCard(settings: Settings) {
    val c = Lifeline.colors
    val app = LocalContext.current.applicationContext as LifelineApp
    val real by app.repository.weather.collectAsStateWithLifecycle()
    val simulatedWeather by app.repository.simWeather.collectAsStateWithLifecycle()
    // Simulation mode shows the simulated storm story; otherwise the real weather from the server.
    val latest = if (settings.simulationMode) simulatedWeather else real
    val aiStatus by app.ai.status.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    CardColumn(R.drawable.ic_water_drop, stringResource(R.string.weather_title)) {
        val pair = latest
        when {
            !settings.simulationMode && settings.serverUrl.isBlank() -> Text(stringResource(R.string.weather_no_server), style = Lifeline.type.body, color = c.inkMuted)
            pair == null -> Text(stringResource(R.string.weather_waiting), style = Lifeline.type.body, color = c.inkMuted)
            else -> {
                val (w, at) = pair
                val facts = remember(w) { WeatherLine.facts(w.condition, w.temperatureC, w.feelsLikeC, w.rain24hMm, w.rainChanceMax, w.gustMax24hKmh) }
                var phrased by remember(w) { mutableStateOf<String?>(null) }
                LaunchedEffect(w, aiStatus, settings.language) {
                    phrased = if (settings.language.tag == "en") app.ai.weather(facts) else null
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    facts.temperatureC?.let { Text("$it °C", style = Lifeline.type.display, color = c.ink) }
                    Text(w.condition, style = Lifeline.type.bodyStrong, color = c.ink, modifier = Modifier.padding(bottom = Space.s))
                }
                val computed = stringResource(R.string.weather_summary, facts.feelsLikeC ?: facts.temperatureC ?: 0,
                    facts.rain24hMm ?: 0, facts.gustMaxKmh ?: 0)
                Text(phrased ?: computed, style = Lifeline.type.body, color = c.ink)
                if (settings.simulationMode) {
                    Text((if (phrased != null) "Written on device · " else "") + stringResource(R.string.weather_simulated),
                        style = Lifeline.type.caption, color = c.warn)
                    com.lifeline.mesh.ui.components.SecondaryButton(stringResource(R.string.weather_next),
                        { app.repository.simWeatherNext.tryEmit(Unit) }, Modifier.fillMaxWidth())
                } else {
                    val stale = now - at > 30 * 60_000L
                    Text(
                        (if (phrased != null) "Written on device · " else "") +
                            stringResource(if (stale) R.string.weather_source_old else R.string.weather_source, clock(at)),
                        style = Lifeline.type.caption, color = if (stale) c.warn else c.inkMuted,
                    )
                }
            }
        }
    }
}

/**
 * My latest watch readings from Health Connect (last 24 hours), read every minute while the SOS tab is open, with the
 * watch alarm switch. A reading outside the limits shows in red; the alarm (in the mesh service) rings for 15 s and
 * then sends an urgent ambulance request. Read only.
 */
@Composable
fun HealthCard(settings: Settings) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val app = context.applicationContext as LifelineApp
    val vitals = remember { WatchVitals(context) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<WatchVitals.Status?>(null) }
    var readings by remember { mutableStateOf<List<VitalReading>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh++ }
    LaunchedEffect(refresh) {
        while (true) {
            status = vitals.status()
            if (status == WatchVitals.Status.READY) readings = vitals.read(System.currentTimeMillis() - 24 * 3_600_000L)
            delay(60_000)
        }
    }
    val now = System.currentTimeMillis()

    CardColumn(R.drawable.ic_health_and_safety, stringResource(R.string.health_title)) {
        LiveWatchSection(settings)
        Text(stringResource(R.string.health_from_hc), style = Lifeline.type.label, color = c.inkMuted, modifier = Modifier.padding(top = Space.s))
        when (status) {
            null -> Text(stringResource(R.string.health_checking), style = Lifeline.type.body, color = c.inkMuted)
            WatchVitals.Status.UNAVAILABLE -> Text(stringResource(R.string.health_unavailable), style = Lifeline.type.body, color = c.inkMuted)
            WatchVitals.Status.UPDATE_NEEDED -> Text(stringResource(R.string.health_update), style = Lifeline.type.body, color = c.inkMuted)
            WatchVitals.Status.NEEDS_PERMISSION -> {
                Text(stringResource(R.string.health_connect_body), style = Lifeline.type.body, color = c.inkMuted)
                PrimaryButton(stringResource(R.string.health_connect), { scope.launch { ask.launch(vitals.permissionsToRequest()) } }, Modifier.fillMaxWidth())
            }
            WatchVitals.Status.READY -> {
                if (readings.isEmpty()) Text(stringResource(R.string.health_none), style = Lifeline.type.body, color = c.inkMuted)
                ReadingRows(readings) { r -> stringResource(R.string.health_ago, ((now - r.atMs) / 60_000).coerceAtLeast(0)) }
            }
        }
        // The alarm covers both sources: the live Bluetooth stream and Health Connect.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.health_alarm), style = Lifeline.type.bodyStrong, color = c.ink)
                Text(stringResource(R.string.health_alarm_body), style = Lifeline.type.caption, color = c.inkMuted)
            }
            Switch(settings.watchVitals, { app.repository.setWatchVitals(it) })
        }
    }
}

/** The newest reading of each kind: label left, value and age right, red when outside the limits. */
@Composable
private fun ReadingRows(readings: List<VitalReading>, age: @Composable (VitalReading) -> String) {
    val c = Lifeline.colors
    listOf(
        VitalReading.Kind.OXYGEN to R.string.health_oxygen,
        VitalReading.Kind.HEART_RATE to R.string.health_heart,
        VitalReading.Kind.BLOOD_PRESSURE to R.string.health_pressure,
    ).forEach { (kind, label) ->
        val r = readings.filter { it.kind == kind }.maxByOrNull { it.atMs } ?: return@forEach
        val value = when (kind) {
            VitalReading.Kind.OXYGEN -> "${r.value.roundToInt()} %"
            VitalReading.Kind.HEART_RATE -> "${r.value.roundToInt()} /min"
            VitalReading.Kind.BLOOD_PRESSURE -> "${r.value.roundToInt()}/${(r.value2 ?: 0.0).roundToInt()} mmHg"
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(label), style = Lifeline.type.body, color = c.inkMuted)
            Text("$value · ${age(r)}", style = Lifeline.type.bodyStrong, color = if (VitalsCheck.outOfRange(r)) c.sos else c.ink)
        }
    }
}

/**
 * Live readings from a Bluetooth watch (10 Oct 2026): connect, the readings as they arrive (every second), and a clear
 * red line when the link is lost ("not being watched"). In simulation mode the simulated watch is offered, with buttons
 * to show low oxygen or to lose the link; its readings are labelled Simulated.
 */
@Composable
private fun LiveWatchSection(settings: Settings) {
    val c = Lifeline.colors
    val app = LocalContext.current.applicationContext as LifelineApp
    val repo = app.repository
    val state by repo.watchState.collectAsStateWithLifecycle()
    val live by repo.liveVitals.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var simMode by remember { mutableStateOf("normal") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val simulated = when (val st = state) {
        is WatchState.Connecting -> st.simulated; is WatchState.Connected -> st.simulated; is WatchState.Lost -> st.simulated; else -> false
    }
    val tag = if (simulated) " · ${stringResource(R.string.simulated_tag)}" else ""

    Text(stringResource(R.string.watch_live_title) + tag, style = Lifeline.type.label, color = c.inkMuted)
    when (val st = state) {
        WatchState.None -> {
            Text(stringResource(R.string.watch_connect_body), style = Lifeline.type.caption, color = c.inkMuted)
            com.lifeline.mesh.ui.components.SecondaryButton(stringResource(R.string.watch_connect), { picking = true }, Modifier.fillMaxWidth())
        }
        is WatchState.Connecting -> Text(stringResource(R.string.watch_connecting, st.name), style = Lifeline.type.body, color = c.ink)
        is WatchState.NoHealthData -> {
            Text(stringResource(R.string.watch_no_data, st.name), style = Lifeline.type.body, color = c.warn)
            com.lifeline.mesh.ui.components.SecondaryButton(stringResource(R.string.watch_disconnect), { repo.setWatch(null) }, Modifier.fillMaxWidth())
        }
        is WatchState.Lost -> {
            Text(stringResource(R.string.watch_lost, st.name, clock(st.at)), style = Lifeline.type.bodyStrong, color = c.sos)
            com.lifeline.mesh.ui.components.SecondaryButton(stringResource(R.string.watch_disconnect), { repo.setWatch(null) }, Modifier.fillMaxWidth())
        }
        is WatchState.Connected -> {
            Text(stringResource(R.string.watch_connected, st.name), style = Lifeline.type.bodyStrong, color = c.safe)
            val recent = live.filter { it.atMs >= st.since - 60_000 }
            if (recent.isEmpty()) Text(stringResource(R.string.watch_waiting), style = Lifeline.type.body, color = c.inkMuted)
            ReadingRows(recent) { r ->
                val s = ((now - r.atMs) / 1000).coerceAtLeast(0)
                if (s <= 2) stringResource(R.string.watch_now) else stringResource(R.string.watch_seconds_ago, s.toInt())
            }
            if (!st.simulated && settings.simulationMode)
                Text(stringResource(R.string.watch_real_in_sim), style = Lifeline.type.caption, color = c.warn)
            if (st.simulated) {
                Text(stringResource(R.string.watch_sim_note), style = Lifeline.type.caption, color = c.inkMuted)
                val pick = { mode: String -> simMode = mode; repo.simWatchCommands.tryEmit(mode) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    com.lifeline.mesh.ui.components.SecondaryButton(stringResource(R.string.watch_sim_low),
                        { pick("low_oxygen") }, Modifier.weight(1f))
                    com.lifeline.mesh.ui.components.SecondaryButton(stringResource(R.string.watch_sim_high_heart),
                        { pick("high_heart") }, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (simMode != "normal") com.lifeline.mesh.ui.components.SecondaryButton(stringResource(R.string.watch_sim_normal),
                        { pick("normal") }, Modifier.weight(1f))
                    com.lifeline.mesh.ui.components.SecondaryButton(stringResource(R.string.watch_sim_lose),
                        { repo.simWatchCommands.tryEmit("lose") }, Modifier.weight(1f))
                }
            }
            com.lifeline.mesh.ui.components.SecondaryButton(stringResource(R.string.watch_disconnect),
                { simMode = "normal"; repo.setWatch(null) }, Modifier.fillMaxWidth())
        }
    }
    if (picking) WatchPickerSheet(settings.simulationMode, onPick = { address, name -> picking = false; repo.setWatch(address, name) },
        onDismiss = { picking = false })
}

/** Finds watches advertising standard health services (and offers the simulated watch in simulation mode). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WatchPickerSheet(simulation: Boolean, onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val scanner = remember { WatchScanner(context) }
    var found by remember { mutableStateOf<Map<String, FoundWatch>>(emptyMap()) }
    val problem = remember { scanner.problem() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        if (problem == null) scanner.start { w -> found = found + (w.address to w) }
        onDispose { scanner.stop() }
    }
    val simulatedName = stringResource(R.string.watch_pick_simulated)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(stringResource(R.string.watch_pick_title), style = Lifeline.type.title, color = c.ink)
            if (simulation) PickRow(simulatedName + " · " + stringResource(R.string.simulated_tag),
                stringResource(R.string.watch_pick_simulated_body)) { onPick(SIMULATED_WATCH, simulatedName) }
            when (problem) {
                WatchScanner.Problem.NO_BLUETOOTH -> Text(stringResource(R.string.watch_pick_no_bt), style = Lifeline.type.body, color = c.warn)
                WatchScanner.Problem.NOT_ALLOWED -> Text(stringResource(R.string.watch_pick_not_allowed), style = Lifeline.type.body, color = c.warn)
                WatchScanner.Problem.OFF -> Text(stringResource(R.string.watch_pick_off), style = Lifeline.type.body, color = c.warn)
                null -> {
                    Text(stringResource(R.string.watch_pick_scanning), style = Lifeline.type.caption, color = c.inkMuted)
                    if (found.isEmpty()) Text(stringResource(R.string.watch_pick_none), style = Lifeline.type.body, color = c.inkMuted)
                    val hr = stringResource(R.string.watch_kind_hr); val ox = stringResource(R.string.watch_kind_oxygen)
                    val bp = stringResource(R.string.watch_kind_bp)
                    found.values.sortedBy { it.name }.forEach { w ->
                        val kinds = listOfNotNull(hr.takeIf { w.heartRate }, ox.takeIf { w.oxygen }, bp.takeIf { w.bloodPressure }).joinToString(", ")
                        PickRow(w.name, kinds) { onPick(w.address, w.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(title: String, body: String, onClick: () -> Unit) {
    val c = Lifeline.colors
    Column(
        Modifier.fillMaxWidth().background(c.bg, Shapes.card)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(title, style = Lifeline.type.bodyStrong, color = c.ink)
        if (body.isNotBlank()) Text(body, style = Lifeline.type.caption, color = c.inkMuted)
    }
}
