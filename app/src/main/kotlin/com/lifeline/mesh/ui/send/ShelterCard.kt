package com.lifeline.mesh.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeline.mesh.AppRepository
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.R
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.packet.PacketType
import com.lifeline.mesh.routing.RoutePlanner
import com.lifeline.mesh.routing.TravelPlan
import com.lifeline.mesh.triage.GeoMath
import com.lifeline.mesh.triage.PlaceReport
import com.lifeline.mesh.triage.Shelter
import com.lifeline.mesh.triage.Shelters
import com.lifeline.mesh.triage.Travel
import com.lifeline.mesh.ui.rescuer.ACTIVE_MS
import com.lifeline.mesh.ui.rescuer.ago
import com.lifeline.mesh.ui.rescuer.distanceText
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The open shelter to walk to, how ([plan], along the roads around blocked ones), and what else is known. */
data class ShelterChoice(val shelter: Shelter?, val plan: TravelPlan?, val fullSkipped: Int, val water: Shelter?, val waterM: Double?)

/**
 * "Where do I go?" (10 Oct 2026): from the community reports this phone holds (shelter open / full, water), the open
 * shelter quickest to reach on foot from my position, along the offline map's roads around reported blocked roads.
 * Recomputed when a report arrives or I move about 25 m. Null until my position is known.
 */
@Composable
fun rememberShelterChoice(repo: AppRepository): ShelterChoice? {
    val packets by repo.packets.collectAsStateWithLifecycle()
    val fix by repo.myFix.collectAsStateWithLifecycle()
    val roads by repo.roads.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val reports = remember(packets, now) {
        packets.filter { it.type == PacketType.REPORT.code && now - it.receivedAt < ACTIVE_MS && it.lat != null && it.lon != null }
            .map { PlaceReport(it.id, it.reportType ?: 0, it.lat!!, it.lon!!, it.note.orEmpty(), it.receivedAt) }
    }
    val blocks = remember(packets) { com.lifeline.mesh.ui.rescuer.blocksFrom(packets) }
    val choice by produceState<ShelterChoice?>(null, reports, fix?.let { Math.round(it.lat * 4000) to Math.round(it.lon * 4000) }, roads, blocks) {
        val me = fix ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.Default) {
            val all = Shelters.places(reports)
            val best = Shelters.quickest(Shelters.openNear(all, me.lat, me.lon)) { s ->
                val p = RoutePlanner.plan(roads, me.lat to me.lon, emptyList(), s.lat to s.lon, Travel.FOOT, blocks)
                p.minutes to p
            }
            val water = all.filter { it.water }.minByOrNull { GeoMath.distanceM(me.lat, me.lon, it.lat, it.lon) }
            ShelterChoice(best?.first, best?.third, Shelters.fullCount(all), water,
                water?.let { GeoMath.distanceM(me.lat, me.lon, it.lat, it.lon) })
        }
    }
    return choice
}

/** SOS tab card: where to go now, on foot. */
@Composable
fun ShelterCard(settings: Settings) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val repo = (context.applicationContext as LifelineApp).repository
    val choice = rememberShelterChoice(repo)
    val now = System.currentTimeMillis()
    Column(
        Modifier.fillMaxWidth().background(c.surface, Shapes.card).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Icon(painterResource(R.drawable.ic_groups), null, tint = c.safe, modifier = Modifier.size(24.dp))
            Text("Where to go", style = Lifeline.type.title, color = c.ink)
        }
        val s = choice?.shelter
        val p = choice?.plan
        when {
            choice == null -> Text("Waiting for your position to find the nearest open shelter.", style = Lifeline.type.body, color = c.inkMuted)
            s == null || p == null -> Text(
                "No open shelter reported near you yet." + if (choice.fullSkipped > 0) " ${choice.fullSkipped} reported full." else "",
                style = Lifeline.type.body, color = c.inkMuted)
            else -> {
                Text("Nearest open shelter", style = Lifeline.type.label, color = c.inkMuted)
                if (s.note.isNotBlank()) Text("“${s.note}”", style = Lifeline.type.bodyStrong, color = c.ink)
                Text("About ${p.minutes} min on foot · ${distanceText(p.meters)}" + if (p.byRoad) " along the roads" else " (straight line)",
                    style = Lifeline.type.headline, color = c.safe)
                val extra = buildList {
                    if (p.avoidedBlocks > 0) add("goes around ${p.avoidedBlocks} blocked road" + if (p.avoidedBlocks > 1) "s" else "")
                    add("reported open ${ago(context, now, s.reportedAt)}" + if (s.reports > 1) " (${s.reports} reports)" else "")
                    if (choice.fullSkipped > 0) add("skipped ${choice.fullSkipped} reported full")
                }
                Text(extra.joinToString(" · ").replaceFirstChar { it.uppercase() } + ".", style = Lifeline.type.body, color = c.ink)
                Text("The route is on the Map tab.", style = Lifeline.type.body, color = c.ink)
            }
        }
        choice?.water?.let { w ->
            Text("Water: ${distanceText(choice.waterM ?: 0.0)} away" + if (w.note.isNotBlank()) " · “${w.note}”" else "",
                style = Lifeline.type.body, color = c.ink)
        }
        Text((if (settings.simulationMode) "Simulated · " else "") +
            "From reports by people nearby. Walking times are estimates on the offline map at 4 km/h.",
            style = Lifeline.type.caption, color = if (settings.simulationMode) c.warn else c.inkMuted)
    }
}
