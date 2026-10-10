package com.lifeline.mesh.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.R
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.packet.PacketType
import com.lifeline.mesh.triage.FindPerson
import com.lifeline.mesh.triage.GeoMath
import com.lifeline.mesh.triage.HeardPerson
import com.lifeline.mesh.triage.PersonAlert
import com.lifeline.mesh.ui.onboarding.teamLabel
import com.lifeline.mesh.ui.rescuer.ACTIVE_MS
import com.lifeline.mesh.ui.rescuer.ago
import com.lifeline.mesh.ui.rescuer.distanceText
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space

/** At most this many answers: a name like "Ravi" can match several people. */
private const val SHOWN = 4

/**
 * "Find someone" on the SOS tab (11 Oct 2026): type a name, see what the mesh has heard of them: checked in safe,
 * asked for help (and who is coming), or only their phone heard. Everything comes from packets this phone holds; people
 * out of reach of the mesh do not show, and the card says so. Rescue teams also see how far away and in which direction
 * (only teams may see people's positions, Phase 10).
 */
@Composable
fun FindCard(settings: Settings) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val repo = (context.applicationContext as LifelineApp).repository
    val packets by repo.packets.collectAsStateWithLifecycle()
    val positions by repo.positions.collectAsStateWithLifecycle()
    val topology by repo.topology.collectAsStateWithLifecycle()
    val fix by repo.myFix.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val teamNames = (3..7).associateWith { androidx.compose.ui.res.stringResource(teamLabel(it)) }

    val people = remember(packets, positions, topology, settings.originId) {
        val now = System.currentTimeMillis()
        val me = settings.originId
        val names = HashMap<Int, MutableSet<String>>()
        fun named(o: Int, n: String?) { if (o != me && !n.isNullOrBlank()) names.getOrPut(o) { linkedSetOf() } += n.trim() }
        val roles = HashMap<Int, HelloRole>()
        topology.forEach { t -> named(t.origin, t.name); HelloRole.fromCode(t.role)?.let { roles[t.origin] = it } }
        positions.values.forEach { p -> named(p.origin, p.name); roles[p.origin] = p.role }
        val recent = packets.filter { !it.isMine && now - it.receivedAt < ACTIVE_MS }
        val checkIns = recent.filter { it.type == PacketType.CHECK_IN.code }.onEach { named(it.origin, it.name) }
            .groupBy { it.origin }.mapValues { (_, l) -> l.maxBy { it.receivedAt } }
        val alerts = recent.filter { it.type == PacketType.ALERT.code }.groupBy { it.origin }.mapValues { (_, l) -> l.maxBy { it.receivedAt } }
        val acks = packets.filter { it.type == PacketType.ACK.code && it.refId != null }
        fun helperName(o: Int) = (names[o]?.firstOrNull() ?: "%08x".format(o).takeLast(4)) +
            (roles[o]?.takeIf { it.isTeam }?.let { r -> teamNames[r.code]?.let { " ($it)" } } ?: "")
        (names.keys + checkIns.keys + alerts.keys).filter { it != me }.distinct().map { o ->
            val ci = checkIns[o]
            val al = alerts[o]
            val heard = listOfNotNull(positions[o]?.let { it.heardAt to it.hops }, topology.firstOrNull { it.origin == o }?.let { it.lastHeard to it.hops })
                .maxByOrNull { it.first }
            HeardPerson(
                origin = o, names = names[o].orEmpty(),
                safeAt = ci?.receivedAt, safeHops = ci?.hops,
                alert = al?.let { a ->
                    PersonAlert(a.receivedAt, Need.fromMask(a.needs ?: 0), a.people ?: 0, a.severity ?: 0, a.hops,
                        acks.filter { it.refId == a.id }.map { if (it.isMine) "You" else helperName(it.origin) }.distinct())
                },
                lastHeardAt = heard?.first, lastHeardHops = heard?.second, isTeam = roles[o]?.isTeam == true,
            )
        }
    }
    val found = remember(query, people) { FindPerson.search(query, people).take(SHOWN) }
    val now = System.currentTimeMillis()

    Column(
        Modifier.fillMaxWidth().background(c.surface, Shapes.card).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Icon(painterResource(R.drawable.ic_person), null, tint = c.link, modifier = Modifier.size(24.dp))
            Text("Find someone", style = Lifeline.type.title, color = c.ink)
        }
        Text("Type a name to see if the mesh has heard from them.", style = Lifeline.type.body, color = c.inkMuted)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(24) },
            label = { Text("Name") },
            singleLine = true,
            textStyle = Lifeline.type.body,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
        if (query.trim().length >= FindPerson.MIN_QUERY) {
            if (found.isEmpty()) Text("Nothing heard from anyone called “${query.trim()}”. They may be out of reach of the mesh, " +
                "or their phone may not have the app.", style = Lifeline.type.body, color = c.ink)
            found.forEach { p ->
                val tone = when (FindPerson.status(p)) {
                    FindPerson.Status.SAFE -> c.safe
                    FindPerson.Status.NEEDS_HELP -> c.sos
                    FindPerson.Status.HEARD -> c.ink
                }
                Column(Modifier.fillMaxWidth().background(c.surfaceRaised, Shapes.card).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(FindPerson.summary(p) { t -> ago(context, now, t) }, style = Lifeline.type.body, color = tone)
                    // Where they are: rescue teams only (Phase 10).
                    if (settings.role.isTeam) {
                        val at = positions[p.origin]?.let { it.lat to it.lon }
                            ?: packets.filter { it.origin == p.origin && it.lat != null && it.lon != null && !(it.lat == 0.0 && it.lon == 0.0) }
                                .maxByOrNull { it.receivedAt }?.let { it.lat!! to it.lon!! }
                        val me = fix
                        if (at != null && me != null) {
                            val d = GeoMath.distanceM(me.lat, me.lon, at.first, at.second)
                            Text("${distanceText(d)} ${GeoMath.compass(GeoMath.bearingDeg(me.lat, me.lon, at.first, at.second))} of you",
                                style = Lifeline.type.caption, color = c.inkMuted)
                        }
                    }
                }
            }
        }
        Text((if (settings.simulationMode) "Simulated · " else "") +
            "From check-ins, alerts and phones this phone has heard in the last 6 hours. Someone out of reach may not show.",
            style = Lifeline.type.caption, color = if (settings.simulationMode) c.warn else c.inkMuted)
    }
}
