package com.lifeline.mesh.ui.rescuer

import androidx.compose.foundation.layout.fillMaxHeight
import android.app.Application
import android.content.Context
import com.lifeline.mesh.ui.components.labelRes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.R
import com.lifeline.mesh.ai.GeoFix
import com.lifeline.mesh.data.PacketEntity
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.location.LocationProvider
import com.lifeline.mesh.packet.Geo
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.PacketType
import com.lifeline.mesh.triage.AlertReport
import com.lifeline.mesh.triage.GeoMath
import com.lifeline.mesh.triage.Merger
import com.lifeline.mesh.triage.RankedAlert
import com.lifeline.mesh.triage.Ranker
import com.lifeline.mesh.ui.components.EmptyState
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.components.StatTile
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The rescuer's own position, for distance and direction. Last known at once, then a fresh fix every minute. */
class RescuerViewModel(app: Application) : AndroidViewModel(app) {
    private val location = LocationProvider(app)
    private val _me = MutableStateFlow<GeoFix?>(null)
    val me: StateFlow<GeoFix?> = _me

    init {
        viewModelScope.launch {
            _me.value = location.lastKnown()
            while (isActive) {
                location.fresh()?.let { _me.value = it }
                delay(60_000)
            }
        }
    }
}

/** Alerts older than this are no longer "active" (store and forward keeps 6 hours too). */
const val ACTIVE_MS = 6 * 60 * 60 * 1000L

/**
 * Ranked merged alerts from stored packets: the same list on the Rescue tab and the Map. An alert whose sender has
 * since checked in safe is done and leaves the list.
 */
fun rankAlerts(packets: List<PacketEntity>, me: GeoFix?, now: Long): List<RankedAlert> {
    val checkIns = checkInsOf(packets)
    return Ranker.rank(
        Merger.merge(packets.filter { it.type == PacketType.ALERT.code && !it.isMine && now - it.receivedAt < ACTIVE_MS }.map { it.toReport() })
            .filterNot { it.closedBy(checkIns) },
        me?.lat, me?.lon,
    )
}

/** "I'm safe" check-ins stored here, as (origin, time). */
fun checkInsOf(packets: List<PacketEntity>): List<Pair<Int, Long>> =
    packets.filter { it.type == PacketType.CHECK_IN.code }.map { it.origin to it.receivedAt }

/** This check-in closed an alert (its sender asked for help before it), so its row leaves the Check-ins list too. */
fun closedATask(checkIn: PacketEntity, packets: List<PacketEntity>): Boolean =
    packets.any { it.type == PacketType.ALERT.code && it.origin == checkIn.origin && it.receivedAt < checkIn.receivedAt }

/**
 * Each responder's open jobs, most urgent first, from the "help is coming" replies stored here. [mine] = only this
 * phone's own replies (the rescuer's own queue); otherwise only other phones' (map lines, the sender's live ETA).
 */
fun queuesFrom(
    packets: List<PacketEntity>, now: Long, done: Set<Pair<Int, Long>> = emptySet(), mine: Boolean = false,
): Map<Int, List<com.lifeline.mesh.triage.Heading>> {
    val acks = packets.filter { it.type == PacketType.ACK.code && it.isMine == mine && it.refId != null }
        .map { com.lifeline.mesh.triage.AckSeen(it.origin, it.refId!!, it.eta ?: 0, it.receivedAt) }
    if (acks.isEmpty()) return emptyMap()
    val alerts = packets.filter { it.type == PacketType.ALERT.code }
        .associate { p -> p.id to p.toReport().let { r -> com.lifeline.mesh.triage.AlertSpot(p.origin, r.lat, r.lon, r.severity) } }
    return com.lifeline.mesh.triage.Responders.queues(acks, alerts, checkInsOf(packets), now, done)
}

/** Each responder's current job: the first of its queue. */
fun headingsFrom(packets: List<PacketEntity>, now: Long, done: Set<Pair<Int, Long>> = emptySet()): Map<Int, com.lifeline.mesh.triage.Heading> =
    queuesFrom(packets, now, done).mapValues { it.value.first() }

/** Packet row → triage input. Lat/lon 0/0 means "no position" (the sender had no fix). */
fun PacketEntity.toReport(): AlertReport {
    val known = lat != null && lon != null && !(lat == 0.0 && lon == 0.0)
    return AlertReport(
        id = id, origin = origin, lat = if (known) lat else null, lon = if (known) lon else null,
        accuracyM = accuracy?.let { Geo.accuracyMeters(it) }, needs = Need.fromMask(needs ?: 0),
        severity = severity ?: 0, people = people ?: 0, note = note ?: "", receivedAt = receivedAt, hops = hops,
        acked = acked, ackEta = ackEta, viaInternet = fromEndpoint == com.lifeline.mesh.mesh.Router.INTERNET,
    )
}

/**
 * Rescue tab (DESIGN.md 8.4): header numbers, situation report (Phase 8), ranked merged alerts, check-ins and community
 * reports. Every count is computed in Kotlin from packets stored on this phone.
 */
@Composable
fun RescuerScreen(settings: Settings, onOpenMesh: () -> Unit, onAsk: () -> Unit, vm: RescuerViewModel = viewModel()) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val repo = (context.applicationContext as LifelineApp).repository
    val packets by repo.packets.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()

    // "2 min ago" labels and the active window move with time.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    val active = packets.filter { now - it.receivedAt < ACTIVE_MS }
    val ranked = remember(active, me) { rankAlerts(active, me, now) }
    val checkIns = remember(active) {
        active.filter { it.type == PacketType.CHECK_IN.code && !it.isMine }.groupBy { it.origin }.map { (_, l) -> l.maxBy { it.receivedAt } }
            .filterNot { closedATask(it, active) }
            .sortedByDescending { it.receivedAt }
    }
    // My own "I'm safe" shows at the top of the list, so the sender can see exactly what rescuers see.
    val myCheckIn = remember(active) {
        active.filter { it.type == PacketType.CHECK_IN.code && it.isMine }.maxByOrNull { it.receivedAt }?.takeUnless { closedATask(it, active) }
    }
    val reports = remember(active) { active.filter { it.type == PacketType.REPORT.code }.sortedByDescending { it.receivedAt } }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    var reporting by rememberSaveable { mutableStateOf(false) }
    // Team view (Phase 10): a team starts on "For you" (its own needs); everyone else on All.
    val focus = com.lifeline.mesh.triage.Teams.focus(settings.role.hello)
    var filter by rememberSaveable(settings.role) { mutableStateOf(if (focus != null) AlertFilter.Focus else AlertFilter.All) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        item { Spacer(Modifier.height(Space.s)) }
        item { com.lifeline.mesh.ui.components.WarningBanner(packets) }
        item { Header(ranked, settings.simulationMode) }
        item {
            val facts = remember(ranked, checkIns) { com.lifeline.mesh.ai.SituationReport.facts(ranked, checkIns.size, ::distanceText) }
            SituationReportCard(facts)
        }
        item { PrimaryButtonRow(onAsk) }
        item { SectionTitle(stringResource(R.string.rescue_alerts) + if (ranked.isEmpty()) "" else " · ${ranked.size}") }
        if (ranked.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.rescue_empty_title),
                    body = stringResource(R.string.rescue_empty_body),
                    actionLabel = stringResource(if (settings.simulationMode) R.string.rescue_load_samples else R.string.rescue_empty_mesh),
                    onAction = { if (settings.simulationMode) loadSamples(context, me) else onOpenMesh() },
                    icon = R.drawable.ic_health_and_safety,
                )
            }
        }
        if (ranked.isNotEmpty()) item { NeedFilters(ranked, focus, filter) { filter = it } }
        val shown = ranked.filter { filter.matches(it, focus) }
        if (ranked.isNotEmpty() && shown.isEmpty()) item { Muted("No alerts of this kind. Tap All to see every alert.") }
        items(shown, key = { it.alert.id }) { r ->
            AlertCard(r, now, onOpen = { open = r.alert.id }, language = settings.language.tag)
        }

        item { SectionTitle(stringResource(R.string.rescue_checkins)) }
        if (checkIns.isEmpty() && myCheckIn == null) item { Muted(stringResource(R.string.rescue_checkins_empty)) }
        myCheckIn?.let { p ->
            item(key = "c-mine") {
                PlaceRow(R.drawable.ic_verified_user, c.safe,
                    stringResource(R.string.rescue_checkin_you, p.name?.ifBlank { null } ?: hex4(p.origin)),
                    stringResource(R.string.rescue_checkin_you_body), now, p.receivedAt)
            }
        }
        items(checkIns, key = { "c${it.id}" }) { p ->
            PlaceRow(R.drawable.ic_verified_user, c.safe, stringResource(R.string.rescue_checkin_row, p.name?.ifBlank { null } ?: hex4(p.origin)),
                placeText(context, me, p), now, p.receivedAt)
        }

        item { SectionTitle(stringResource(R.string.rescue_reports)) }
        if (reports.isEmpty()) item { Muted(stringResource(R.string.rescue_reports_empty)) }
        item { SecondaryButton(stringResource(R.string.report_add), { reporting = true }, Modifier.fillMaxWidth()) }
        items(reports, key = { "r${it.id}" }) { p ->
            val title = when (p.reportType) { 1 -> R.string.report_1; 2 -> R.string.report_2; 3 -> R.string.report_3; else -> R.string.report_4 }
            val icon = when (p.reportType) { 1 -> R.drawable.ic_block; 2 -> R.drawable.ic_water_drop; else -> R.drawable.ic_groups }
            PlaceRow(icon, c.warn, stringResource(title) + (p.note?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                placeText(context, me, p), now, p.receivedAt)
        }

        if (settings.simulationMode && ranked.isNotEmpty()) {
            item { SecondaryButton(stringResource(R.string.rescue_load_samples), { loadSamples(context, me) }, Modifier.fillMaxWidth()) }
        }
        item { Spacer(Modifier.height(Space.xxl)) }
    }

    open?.let { id ->
        val r = ranked.firstOrNull { it.alert.id == id }
        if (r == null) open = null
        else AlertDetailSheet(r, now, onSend = { eta, note -> repo.sendAcks(r.alert.reports.map { it.id }, eta, note); open = null }, onDismiss = { open = null },
            language = settings.language.tag)
    }
    if (reporting) ReportSheet(onDismiss = { reporting = false })
}

private fun loadSamples(context: Context, me: GeoFix?) {
    val repo = (context.applicationContext as LifelineApp).repository
    // Around the rescuer if we know where that is, else around the venue.
    repo.loadSampleAlerts(me?.lat ?: 12.9716, me?.lon ?: 77.5946)
}

@Composable
private fun Header(ranked: List<RankedAlert>, simulated: Boolean) {
    val people = ranked.sumOf { it.alert.people }
    val urgent = ranked.count { it.alert.severity >= 3 }
    val acked = ranked.count { it.alert.acked }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile(ranked.size.toString(), stringResource(R.string.rescue_active), Modifier.weight(1f).fillMaxHeight(), simulated = simulated)
            StatTile(people.toString(), stringResource(R.string.rescue_people), Modifier.weight(1f).fillMaxHeight(), simulated = simulated)
        }
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile(urgent.toString(), stringResource(R.string.rescue_urgent), Modifier.weight(1f).fillMaxHeight(), simulated = simulated)
            StatTile(acked.toString(), stringResource(R.string.rescue_acked), Modifier.weight(1f).fillMaxHeight(), simulated = simulated)
        }
    }
}

/** "Ask the mesh" entry (DESIGN.md 8.4 floating Ask button, placed in the list so it never covers a card). */
@Composable
private fun PrimaryButtonRow(onAsk: () -> Unit) {
    com.lifeline.mesh.ui.components.PrimaryButton("Ask the mesh", onAsk, Modifier.fillMaxWidth())
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = Lifeline.type.title, color = Lifeline.colors.ink, modifier = Modifier.padding(top = Space.s).semantics { heading() })

@Composable
private fun Muted(text: String) = Text(text, style = Lifeline.type.body, color = Lifeline.colors.inkMuted)

@Composable
private fun PlaceRow(icon: Int, tone: androidx.compose.ui.graphics.Color, title: String, place: String, now: Long, at: Long) {
    val c = Lifeline.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface, Shapes.card).padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Box(Modifier.size(40.dp).background(c.surfaceRaised, Shapes.circle), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = tone, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Lifeline.type.bodyStrong, color = c.ink)
            Text("$place · ${ago(LocalContext.current, now, at)}", style = Lifeline.type.caption, color = c.inkMuted)
        }
    }
}

/** "400 m NE", or "Location unknown". */
fun placeText(context: Context, me: GeoFix?, p: PacketEntity): String {
    val r = p.toReport()
    if (r.lat == null || r.lon == null) return context.getString(R.string.alert_location_unknown)
    if (me == null) return "%.5f, %.5f".format(r.lat, r.lon)
    return distanceText(GeoMath.distanceM(me.lat, me.lon, r.lat, r.lon)) + " " + GeoMath.compass(GeoMath.bearingDeg(me.lat, me.lon, r.lat, r.lon))
}

fun distanceText(m: Double): String = if (m < 1000) "${(m / 10).toInt() * 10} m" else "%.1f km".format(m / 1000)

fun ago(context: Context, now: Long, at: Long): String {
    val min = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> context.getString(R.string.ago_now)
        min < 60 -> context.getString(R.string.ago_min, min)
        else -> context.getString(R.string.ago_hour, min / 60)
    }
}

fun hex4(origin: Int) = "%08x".format(origin).takeLast(4)

/** Which alerts the Rescue list shows (Phase 10 team views). Ranks stay global, so #n is the same everywhere. */
sealed interface AlertFilter : java.io.Serializable {
    fun matches(r: RankedAlert, focus: Set<Need>?): Boolean
    data object All : AlertFilter { private fun readResolve(): Any = All; override fun matches(r: RankedAlert, focus: Set<Need>?) = true }
    data object Focus : AlertFilter {
        private fun readResolve(): Any = Focus
        override fun matches(r: RankedAlert, focus: Set<Need>?) = focus == null || r.alert.needs.any { it in focus }
    }
    data class One(val need: Need) : AlertFilter { override fun matches(r: RankedAlert, focus: Set<Need>?) = need in r.alert.needs }
}

/** "For you (Ambulance) · 3", "All · 7", then one chip per need present, each with its count. */
@Composable
private fun NeedFilters(ranked: List<RankedAlert>, focus: Set<Need>?, current: AlertFilter, onPick: (AlertFilter) -> Unit) {
    val c = Lifeline.colors
    val repo = (LocalContext.current.applicationContext as LifelineApp).repository
    val role = repo.settings.collectAsStateWithLifecycle().value?.role
    val chips = buildList {
        if (focus != null && role != null) {
            add(AlertFilter.Focus to "For you (${stringResource(com.lifeline.mesh.ui.onboarding.teamLabel(role.code))}) · ${ranked.count { AlertFilter.Focus.matches(it, focus) }}")
        }
        add(AlertFilter.All to "All · ${ranked.size}")
        (Need.entries).forEach { n ->
            val k = ranked.count { n in it.alert.needs }
            if (k > 0) add(AlertFilter.One(n) to "${stringResource(n.labelRes())} · $k")
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        chips.forEach { (f, label) ->
            val on = f == current
            Box(
                Modifier.heightIn(min = com.lifeline.mesh.ui.theme.Touch.standard)
                    .background(if (on) c.surfaceRaised else c.surface, Shapes.chip)
                    .border(if (on) 2.dp else 1.dp, if (on) c.link else c.outline, Shapes.chip)
                    .clickable(role = androidx.compose.ui.semantics.Role.Tab) { onPick(f) }
                    .padding(horizontal = Space.m),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = Lifeline.type.label, color = if (on) c.ink else c.inkMuted) }
        }
    }
}
