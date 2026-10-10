package com.lifeline.mesh.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.ai.PathFacts
import com.lifeline.mesh.ai.PathLine
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.mesh.MeshPath
import com.lifeline.mesh.mesh.PositionBeacon
import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.PacketType
import com.lifeline.mesh.ui.onboarding.teamLabel
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.delay

/**
 * One mesh path to draw: the line ([points], in path order, only phones whose position is known here), numbered hop
 * badges (lat, lon, hop number) and the facts for its sentence.
 */
data class DrawnPath(val points: List<Pair<Double, Double>>, val badges: List<Triple<Double, Double, Int>>, val facts: PathFacts)

/** At most this many paths on the map: more would be a tangle. */
private const val MAX_PATHS = 3

/**
 * Mesh paths for the map (11 Oct 2026, on request). Someone waiting for help sees how their alert reaches each team
 * that said "help is coming" (or the nearest team in reach before anyone answers): a line from them to the team with
 * the hop count, but not the other people's positions on the way (Phase 10: only teams see people). A rescue team sees
 * the full path, phone by phone, to each person whose alert it accepted. The path is the fewest hops through the
 * Hello graph this phone knows (MeshPath), so it is how messages travel now.
 */
@Composable
fun rememberMeshPaths(settings: Settings): List<DrawnPath> {
    val repo = (LocalContext.current.applicationContext as LifelineApp).repository
    val packets by repo.packets.collectAsStateWithLifecycle()
    val positions by repo.positions.collectAsStateWithLifecycle()
    val topology by repo.topology.collectAsStateWithLifecycle()
    val origins by repo.neighborOrigins.collectAsStateWithLifecycle()
    val fix by repo.myFix.collectAsStateWithLifecycle()
    val done by repo.jobsDone.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    val teamNames = (3..7).associateWith { stringResource(teamLabel(it)) }

    return remember(packets, positions, topology, origins, fix?.let { Math.round(it.lat * 4000) to Math.round(it.lon * 4000) },
        done, settings.role, now) {
        val me = settings.originId
        val myPos = fix?.let { it.lat to it.lon } ?: return@remember emptyList()
        val links = origins.values.map { me to it } +
            topology.flatMap { t -> t.neighbors.split(',').mapNotNull { it.trim().toIntOrNull() }.map { t.origin to it } }
        val names = topology.associate { it.origin to it.name } + positions.values.associate { it.origin to it.name }
        val roles = topology.mapNotNull { t -> HelloRole.fromCode(t.role)?.let { t.origin to it } }.toMap() +
            positions.values.associate { it.origin to it.role }
        val fresh = positions.filterValues { now - it.heardAt < PositionBeacon.SHOW_MS && !it.viaInternet }
        fun pos(o: Int) = if (o == me) myPos else fresh[o]?.let { it.lat to it.lon }
        fun name(o: Int) = names[o]?.ifBlank { null } ?: "%08x".format(o).takeLast(4)
        fun teamText(o: Int) = name(o) + (roles[o]?.let { r -> teamNames[r.code]?.let { " ($it)" } } ?: "")

        if (!settings.role.isTeam) {
            // Teams coming to my alerts; before anyone answers, the nearest team in reach (fewest hops).
            val myAlerts = packets.filter { it.isMine && it.type == PacketType.ALERT.code }.mapTo(HashSet()) { it.id }
            val coming = com.lifeline.mesh.ui.rescuer.headingsFrom(packets, now, done).values
                .filter { it.alertId in myAlerts }.map { it.responder }.distinct()
            val teams = coming.ifEmpty {
                roles.filterValues { it.isTeam }.keys.mapNotNull { t -> MeshPath.shortest(me, t, links)?.let { t to it.size } }
                    .sortedWith(compareBy({ it.second }, { it.first })).take(1).map { it.first }
            }
            teams.take(MAX_PATHS).mapNotNull { t ->
                val path = MeshPath.shortest(me, t, links) ?: return@mapNotNull null
                val tp = pos(t) ?: return@mapNotNull null
                val hops = path.size - 1
                DrawnPath(listOf(myPos, tp), listOf(Triple((myPos.first + tp.first) / 2, (myPos.second + tp.second) / 2, hops)),
                    PathFacts(teamText(t), name(t), "you", path.drop(1).dropLast(1).map(::name), mine = true))
            }
        } else {
            // My accepted jobs: the path to each person, phone by phone.
            val jobs = com.lifeline.mesh.ui.rescuer.queuesFrom(packets, now, done, mine = true).values.firstOrNull().orEmpty()
            val senders = jobs.mapNotNull { h -> packets.firstOrNull { it.id == h.alertId }?.origin }.distinct()
            senders.take(MAX_PATHS).mapNotNull { s ->
                val path = MeshPath.shortest(me, s, links) ?: return@mapNotNull null
                val pts = path.mapNotNull { o -> pos(o)?.let { o to it } }
                if (pts.size < 2) return@mapNotNull null
                val index = path.withIndex().associate { it.value to it.index }
                val badges = pts.zipWithNext().map { (a, b) ->
                    Triple((a.second.first + b.second.first) / 2, (a.second.second + b.second.second) / 2, index.getValue(b.first))
                }
                DrawnPath(pts.map { it.second }, badges,
                    PathFacts(teamText(me).replaceFirst(name(me), "you"), name(s), name(s), path.drop(1).dropLast(1).map(::name), mine = false))
            }
        }
    }
}

/** The first path's sentence over the map: written on the phone if the model is ready, else the computed one. */
@Composable
fun MeshPathBanner(paths: List<DrawnPath>, modifier: Modifier = Modifier) {
    val first = paths.firstOrNull() ?: return
    val c = Lifeline.colors
    val app = LocalContext.current.applicationContext as LifelineApp
    val aiStatus by app.ai.status.collectAsStateWithLifecycle()
    var phrased by remember(first.facts) { mutableStateOf<String?>(null) }
    LaunchedEffect(first.facts, aiStatus) { phrased = app.ai.path(first.facts) }
    Column(
        modifier.fillMaxWidth().padding(Space.s).background(c.bg.copy(alpha = 0.92f), Shapes.card).padding(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(phrased ?: PathLine.template(first.facts), style = Lifeline.type.body, color = c.ink)
        Text((if (phrased != null) "Written on device · " else "") + "Mesh path: the fewest hops this phone knows of.",
            style = Lifeline.type.caption, color = c.inkMuted)
    }
}
