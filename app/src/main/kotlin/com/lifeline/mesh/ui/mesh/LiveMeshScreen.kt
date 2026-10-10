package com.lifeline.mesh.ui.mesh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeline.mesh.AppRepository
import com.lifeline.mesh.R
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.mesh.LinkState
import com.lifeline.mesh.mesh.MeshHealth
import com.lifeline.mesh.mesh.MeshService
import com.lifeline.mesh.mesh.MeshSimulation
import com.lifeline.mesh.ui.components.EmptyState
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.delay

/** Collapsed sheet before the scoreboard is measured; then exactly handle + scoreboard (DESIGN.md 8.3). */
private val PEEK = 152.dp
/** Drag handle: 8 dp + 4 dp + 8 dp. */
private val HANDLE = 20.dp

/**
 * The Live Mesh screen (DESIGN.md 8.3): the mesh canvas full-bleed, the Scoreboard in a draggable sheet that
 * expands into the event timeline, a node sheet on tap, and simulation controls when simulation mode is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveMeshScreen(settings: Settings, repository: AppRepository, onDebug: () -> Unit, onPermissions: () -> Unit) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val running by repository.meshRunning.collectAsStateWithLifecycle()
    val neighbors by repository.neighbors.collectAsStateWithLifecycle()
    val origins by repository.neighborOrigins.collectAsStateWithLifecycle()
    val topology by repository.topology.collectAsStateWithLifecycle()
    val waiting by repository.waiting.collectAsStateWithLifecycle()
    val metrics by repository.metrics.collectAsStateWithLifecycle()
    val events by repository.events.collectAsStateWithLifecycle()
    val packets by repository.packets.collectAsStateWithLifecycle()
    val serviceHealth by repository.meshHealth.collectAsStateWithLifecycle()
    val simulation by repository.simulation.collectAsStateWithLifecycle()
    val power by repository.powerMode.collectAsStateWithLifecycle()
    val gaps = rememberGaps(settings, repository)
    var showGaps by rememberSaveable { mutableStateOf(false) }
    val myBattery by repository.myBattery.collectAsStateWithLifecycle()

    // Lost links stay visible for 10 s: re-evaluate once a second while any are on screen.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anyLost = neighbors.any { it.state == LinkState.LOST }
    LaunchedEffect(anyLost) { while (anyLost) { delay(1_000); now = System.currentTimeMillis() } }
    val graph = remember(neighbors, origins, topology, settings.originId, settings.displayName, if (anyLost) now / 1_000 else 0L) {
        MeshGraph.build(settings.originId, settings.displayName, neighbors, origins, topology, System.currentTimeMillis())
    }
    val myIds = remember(packets) { packets.filter { it.isMine }.mapTo(HashSet()) { it.id } }

    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumes++; onPauseOrDispose { } }
    val health = remember(resumes, serviceHealth) { MeshHealth.check(context, serviceHealth.radioError) }

    // Demo mode: keep the screen awake while this screen is showing.
    val view = LocalView.current
    DisposableEffect(settings.demoMode) {
        view.keepScreenOn = settings.demoMode
        onDispose { view.keepScreenOn = false }
    }

    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(EventFilter.ALL) }
    val labels = meshLabels()
    val sheetMax = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    // The tiles grow with font scale and the Simulated tag, so the peek follows their measured height: collapsed shows
    // exactly the scoreboard, never a sliver of the timeline heading.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var scorePx by remember { mutableIntStateOf(0) }
    val peek = if (scorePx == 0) PEEK else with(density) { scorePx.toDp() } + HANDLE + Space.s

    BottomSheetScaffold(
        scaffoldState = rememberBottomSheetScaffoldState(),
        sheetPeekHeight = peek,
        sheetContainerColor = c.surface,
        sheetContentColor = c.ink,
        sheetShape = Shapes.sheet,
        sheetShadowElevation = 0.dp,
        sheetTonalElevation = 0.dp,
        containerColor = c.bg,
        sheetDragHandle = {
            Box(Modifier.padding(top = Space.s, bottom = Space.s).size(width = 36.dp, height = 4.dp).background(c.outline, Shapes.circle))
        },
        sheetContent = {
            Column(Modifier.fillMaxWidth().heightIn(max = sheetMax).padding(horizontal = Space.screen)) {
                Scoreboard(metrics, simulated = settings.simulationMode,
                    modifier = Modifier.onSizeChanged { scorePx = it.height })
                Spacer(Modifier.height(Space.l))
                Text(stringResource(R.string.timeline_title), style = Lifeline.type.title, color = c.ink,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(Space.s))
                EventFilterChips(filter, { filter = it })
                Spacer(Modifier.height(Space.s))
                EventTimeline(events, filter, onEmptyAction = onDebug, modifier = Modifier.fillMaxWidth().weight(1f, fill = false))
                Spacer(Modifier.height(Space.l))
            }
        },
    ) { inner ->
        // `inner` already reserves the sheet's peek height.
        Box(Modifier.fillMaxSize().padding(inner)) {
            if (!running) {
                val canStart = settings.simulationMode || health.permissionsOk
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = stringResource(R.string.mesh_off_title),
                        body = stringResource(R.string.mesh_off_body),
                        actionLabel = stringResource(if (canStart) R.string.mesh_start else R.string.mesh_grant),
                        onAction = {
                            if (canStart) { repository.userStoppedMesh = false; MeshService.start(context) } else onPermissions()
                        },
                        icon = R.drawable.ic_hub,
                    )
                }
            } else {
            val searching = graph.connectedCount == 0
            // The simulation buttons sit at the bottom of this area: keep the nodes above them.
            var controlsPx by remember { mutableIntStateOf(0) }
            val controlsDp = if (simulation == null) 0.dp else with(density) { controlsPx.toDp() } + Space.s
            MeshCanvas(
                graph = graph,
                activity = repository.linkActivity,
                waiting = waiting,
                searching = searching,
                demo = settings.demoMode,
                selected = selected,
                isMine = { it in myIds },
                labels = labels,
                onNodeTap = { selected = it },
                modifier = Modifier.fillMaxSize().padding(bottom = controlsDp),
            )

            Column(Modifier.fillMaxWidth().padding(Space.screen), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (power != com.lifeline.mesh.mesh.PowerMode.NORMAL) SavingBanner(power, myBattery)
                GapsBanner(gaps) { showGaps = true }
                firstProblem(health, settings.simulationMode)?.let { text ->
                    Row(
                        Modifier.fillMaxWidth().background(c.surface, Shapes.card).border(1.dp, c.warn, Shapes.card).padding(Space.m),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        Icon(painterResource(R.drawable.ic_warning), null, tint = c.warn, modifier = Modifier.size(20.dp))
                        Text(text, style = Lifeline.type.caption, color = c.ink, modifier = Modifier.weight(1f))
                        SecondaryButton(stringResource(R.string.mesh_fix), onDebug)
                    }
                }
            }
            simulation?.let {
                SimControls(it, Modifier.align(Alignment.BottomCenter).padding(bottom = Space.s).onSizeChanged { sz -> controlsPx = sz.height })
            }

            if (searching) {
                Text(
                    stringResource(R.string.mesh_searching),
                    style = Lifeline.type.body, color = c.inkMuted, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = Space.xxl, vertical = Space.l),
                )
            }
            }
        }
    }

    if (showGaps) {
        ModalBottomSheet(
            onDismissRequest = { showGaps = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.surface,
            shape = Shapes.sheet,
        ) { GapsList(gaps, settings.simulationMode) }
    }

    selected?.let { key ->
        val node = graph.node(key)
        if (node == null) {
            selected = null
        } else {
            ModalBottomSheet(
                onDismissRequest = { selected = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = c.surface,
                shape = Shapes.sheet,
            ) {
                NodeDetails(node, graph, packetsFrom = packets.count { node.origin != null && it.origin == node.origin }, waiting = waiting)
            }
        }
    }
}

/** One scrollable row under the canvas, so it never covers the nodes. */
/** The virtual phone the demo runs down: Ravi links this phone to five others, so his battery matters. */
private const val SIM_DRAIN = "Ravi"

@Composable
private fun SimControls(sim: MeshSimulation, modifier: Modifier = Modifier) {
    val online by sim.online.collectAsStateWithLifecycle()
    val low by sim.lowBattery.collectAsStateWithLifecycle()
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.screen),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        MeshSimulation.PHONES.forEach { name ->
            val on = online[name] != false
            SecondaryButton(
                stringResource(R.string.sim_phone, name, stringResource(if (on) R.string.sim_on else R.string.sim_off)),
                { sim.setOnline(name, !on) },
            )
        }
        SecondaryButton(stringResource(R.string.sim_alert), { sim.nextIncident() })
        SecondaryButton(stringResource(R.string.sim_block_road), { sim.blockRoadAhead() })
        SecondaryButton("Fill the nearest shelter", { sim.fillNearestShelter() })
        val raviLow = SIM_DRAIN in low
        SecondaryButton(if (raviLow) "$SIM_DRAIN's battery back" else "$SIM_DRAIN's battery to ${MeshSimulation.LOW_BATTERY} %",
            { sim.setLowBattery(SIM_DRAIN, !raviLow) })
    }
}

/** This phone is in low-battery survival mode: what it still does for others, in one line. */
@Composable
private fun SavingBanner(mode: com.lifeline.mesh.mesh.PowerMode, battery: Int?) {
    val c = Lifeline.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface, Shapes.card).border(1.dp, c.warn, Shapes.card).padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(painterResource(R.drawable.ic_battery_alert), null, tint = c.warn, modifier = Modifier.size(20.dp))
        Text(
            (battery?.let { "Battery $it %: " } ?: "") + savingText(mode) + " The rest waits until this phone charges.",
            style = Lifeline.type.caption, color = c.ink, modifier = Modifier.weight(1f),
        )
    }
}

/** What a phone in survival mode still passes on for others (PowerPolicy.relays). */
fun savingText(mode: com.lifeline.mesh.mesh.PowerMode): String = when (mode) {
    com.lifeline.mesh.mesh.PowerMode.NORMAL -> "Passing on everything."
    com.lifeline.mesh.mesh.PowerMode.SAVER -> "Saving battery: passing on alerts, replies, \"I'm safe\", warnings and positions only."
    com.lifeline.mesh.mesh.PowerMode.CRITICAL -> "Critical battery: passing on urgent alerts, replies and \"I'm safe\" only."
}

@Composable
private fun NodeDetails(node: GraphNode, graph: MeshGraph, packetsFrom: Int, waiting: Int) {
    val c = Lifeline.colors
    val you = stringResource(R.string.mesh_you)
    val links = graph.neighborsOf(node.key).mapNotNull { k ->
        graph.node(k)?.let { n -> if (n.ring == Ring.ME) you else n.name.ifBlank { null } }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(
            if (node.ring == Ring.ME) stringResource(R.string.node_you) else node.name,
            style = Lifeline.type.headline, color = c.ink, modifier = Modifier.semantics { heading() },
        )
        if (node.origin != null) Text("%08x".format(node.origin), style = Lifeline.type.caption, color = c.inkMuted)
        if (node.ring != Ring.ME) {
            Text(
                when (node.role) { 1 -> stringResource(R.string.node_role_1); 2 -> stringResource(R.string.node_role_2)
                    3 -> stringResource(R.string.node_role_3); 4 -> stringResource(R.string.node_role_4)
                    5 -> stringResource(R.string.node_role_5); 6 -> stringResource(R.string.node_role_6)
                    7 -> stringResource(R.string.node_role_7); else -> stringResource(R.string.node_role_unknown) },
                style = Lifeline.type.bodyStrong, color = c.ink,
            )
            Text(
                if (node.ring == Ring.DIRECT) stringResource(R.string.node_direct) else stringResource(R.string.node_hops, node.hops),
                style = Lifeline.type.body, color = c.ink,
            )
            Text(node.battery?.let { stringResource(R.string.node_battery, it) } ?: stringResource(R.string.node_battery_unknown),
                style = Lifeline.type.body, color = c.ink)
            // Its Hello carries its battery, so its survival mode is known here too.
            val mode = com.lifeline.mesh.mesh.PowerPolicy.modeOf(node.battery)
            if (mode != com.lifeline.mesh.mesh.PowerMode.NORMAL) Text(savingText(mode), style = Lifeline.type.body, color = c.warn)
            node.lastHeard?.let {
                val s = ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0)
                Text(stringResource(R.string.node_heard, if (s < 60) "${s}s ago" else "${s / 60}m ago"), style = Lifeline.type.body, color = c.ink)
            }
            Text(stringResource(R.string.node_packets, packetsFrom), style = Lifeline.type.body, color = c.ink)
        } else {
            Text(stringResource(R.string.node_waiting, waiting), style = Lifeline.type.body, color = c.ink)
        }
        Text(
            if (links.isEmpty()) stringResource(R.string.node_links_none) else stringResource(R.string.node_links, links.joinToString()),
            style = Lifeline.type.body, color = c.inkMuted,
        )
        Spacer(Modifier.width(Space.s))
    }
}

@Composable
private fun firstProblem(h: MeshHealth, simulated: Boolean): String? = when {
    simulated -> null // the simulator needs no radios
    !h.permissionsOk -> stringResource(R.string.mesh_problem_permissions)
    !h.playServicesOk -> stringResource(R.string.mesh_problem_play)
    h.radioError != null -> h.radioError
    h.hasBluetooth && !h.bluetoothOn -> stringResource(R.string.mesh_problem_bluetooth)
    !h.locationOn -> stringResource(R.string.mesh_problem_location)
    !h.batteryExempt -> stringResource(R.string.mesh_problem_battery)
    else -> null
}

@Composable
private fun meshLabels(): MeshLabels {
    val alert = stringResource(R.string.mesh_label_alert)
    val reply = stringResource(R.string.mesh_label_reply)
    val help = stringResource(R.string.status_help_coming)
    val checkIn = stringResource(R.string.mesh_label_checkin)
    val report = stringResource(R.string.mesh_label_report)
    val ping = stringResource(R.string.mesh_label_ping)
    val echo = stringResource(R.string.mesh_label_echo)
    val waiting = stringResource(R.string.mesh_label_waiting)
    val you = stringResource(R.string.mesh_you)
    return remember(alert, waiting, you) {
        MeshLabels(
            alert = { alert.format(it) }, reply = { reply.format(it) }, helpComing = help,
            checkIn = { checkIn.format(it) }, report = { report.format(it) }, ping = { ping.format(it) },
            echo = { echo.format(it) }, waiting = { waiting.format(it) }, you = you,
        )
    }
}
