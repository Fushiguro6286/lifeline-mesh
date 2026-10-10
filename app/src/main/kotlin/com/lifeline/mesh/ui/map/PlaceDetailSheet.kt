package com.lifeline.mesh.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.ai.GeoFix
import com.lifeline.mesh.data.Settings
import com.lifeline.mesh.packet.HelloRole
import com.lifeline.mesh.triage.GeoMath
import com.lifeline.mesh.triage.Teams
import com.lifeline.mesh.ui.components.labelRes
import com.lifeline.mesh.ui.onboarding.teamLabel
import com.lifeline.mesh.ui.rescuer.ago
import com.lifeline.mesh.ui.rescuer.distanceText
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space

/**
 * Details of a point on the map (Phase 10): a phone's live position, a check-in or a community report. Distance and
 * direction from me, when it was heard and over how many hops, position accuracy, and for live positions a travel-time
 * estimate (a team coming to me, or me going to someone who needs help), computed in Kotlin and labelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailSheet(
    place: Place,
    me: GeoFix?,
    settings: Settings,
    onDismiss: () -> Unit,
    /** For a responder's live position: its current job and that alert, if known. */
    heading: com.lifeline.mesh.triage.Heading? = null,
    target: com.lifeline.mesh.data.PacketEntity? = null,
) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val role = if (place.kind >= 11) HelloRole.fromCode(place.kind - 10) else null
    val (icon, tone) = placeIcon(place.kind, c)
    val title = when {
        role != null -> place.label.ifBlank { "Phone" }
        place.kind == 0 -> stringResource(R.string.place_safe, place.label.ifBlank { "Someone" })
        else -> stringResource(reportLabel(place.kind))
    }
    val subtitle = when {
        role == null -> null
        role.isTeam -> stringResource(teamLabel(role.code))
        role == HelloRole.NEEDS_HELP -> stringResource(R.string.place_role_1)
        else -> stringResource(R.string.place_role_2)
    }
    val distance = me?.let { GeoMath.distanceM(it.lat, it.lon, place.lat, place.lon) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = Shapes.sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Icon(painterResource(icon), null, tint = tone, modifier = Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = Lifeline.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })
                    subtitle?.let { Text(it, style = Lifeline.type.bodyStrong, color = tone) }
                }
            }
            if (settings.simulationMode) Text(stringResource(R.string.simulated_tag), style = Lifeline.type.label, color = c.warn)
            if (place.note.isNotBlank()) Text("“${place.note}”", style = Lifeline.type.body, color = c.ink)

            distance?.let { d ->
                val dir = GeoMath.compass(GeoMath.bearingDeg(me!!.lat, me.lon, place.lat, place.lon))
                Text(stringResource(R.string.place_distance, distanceText(d), dir), style = Lifeline.type.body, color = c.ink)
                when {
                    // Its job is shown below instead.
                    heading != null -> Unit
                    // A team on its way to someone like me: at its usual speed.
                    role != null && role.isTeam && !settings.role.isTeam ->
                        Text(stringResource(R.string.place_reach_you, Teams.etaMinutes(d, Teams.defaultTravel(role))),
                            style = Lifeline.type.body, color = c.link)
                    // Someone I could go and help: at my own speed.
                    role != null && settings.role != com.lifeline.mesh.data.Role.NEEDS_HELP && !role.isTeam ->
                        Text(stringResource(R.string.place_you_reach, Teams.etaMinutes(d, settings.travel), settings.travel.label),
                            style = Lifeline.type.body, color = c.link)
                    else -> Unit
                }
            } ?: Text(stringResource(R.string.place_no_me), style = Lifeline.type.body, color = c.inkMuted)

            if (role != null && role != HelloRole.NEEDS_HELP) HeadingBlock(place, role, heading, target, settings, now)

            if (place.at > 0) {
                val heard = ago(context, now, place.at)
                Text(
                    if (place.hops != null) stringResource(R.string.place_heard_hops, heard, place.hops) else stringResource(R.string.place_heard, heard),
                    style = Lifeline.type.body, color = c.inkMuted,
                )
            }
            place.accuracyM?.let { Text(stringResource(R.string.place_accuracy, it), style = Lifeline.type.caption, color = c.inkMuted) }
            if (role != null) Text(stringResource(R.string.place_live), style = Lifeline.type.caption, color = c.inkMuted)
            Text("%.5f, %.5f".format(place.lat, place.lon), style = Lifeline.type.caption, color = c.inkMuted)
        }
    }
}

/**
 * Where a team or volunteer is going: from its "help is coming" replies (oldest open job first). Teams see the job and
 * its distance; anyone else sees only whether it is coming to them, never another person's alert.
 */
@Composable
private fun HeadingBlock(
    place: Place, role: HelloRole, heading: com.lifeline.mesh.triage.Heading?, target: com.lifeline.mesh.data.PacketEntity?,
    settings: Settings, now: Long,
) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val toMe = target?.isMine == true
    val live = heading?.lat?.let { lat -> heading.lon?.let { lon -> com.lifeline.mesh.triage.Responders.liveEta(place.lat, place.lon, lat, lon, role) } }
    when {
        heading == null -> Text(stringResource(R.string.place_heading_none), style = Lifeline.type.body, color = c.inkMuted)
        toMe -> Text(
            live?.let { stringResource(R.string.place_heading_you, it.second) } ?: stringResource(R.string.place_heading_you_nopos),
            style = Lifeline.type.bodyStrong, color = c.safe,
        )
        !settings.role.isTeam -> Text(stringResource(R.string.place_heading_other), style = Lifeline.type.body, color = c.ink)
        else -> {
            val res = LocalResources.current
            val needs = com.lifeline.mesh.packet.Need.fromMask(target?.needs ?: 0)
            val what = if (com.lifeline.mesh.packet.Need.PANIC in needs) stringResource(R.string.alert_panic)
            else needs.joinToString(" · ") { res.getString(it.labelRes()) }
            val people = target?.people?.takeIf { it > 0 }?.let { if (it == 1) stringResource(R.string.alert_one_person) else stringResource(R.string.alert_people, it) }
            Text(stringResource(R.string.place_heading, listOfNotNull(what.ifBlank { null }, people).joinToString(", ").ifBlank { "an alert" }),
                style = Lifeline.type.bodyStrong, color = c.link)
            target?.note?.takeIf { it.isNotBlank() }?.let { Text("“$it”", style = Lifeline.type.body, color = c.ink) }
            Text(
                live?.let { stringResource(R.string.place_heading_eta, distanceText(it.first), it.second, Teams.defaultTravel(role).label) }
                    ?: stringResource(R.string.place_heading_nopos),
                style = Lifeline.type.body, color = c.ink,
            )
        }
    }
    if (heading != null && (toMe || settings.role.isTeam)) {
        Text(stringResource(R.string.place_heading_said, heading.promisedEta, ago(context, now, heading.promisedAtMs)),
            style = Lifeline.type.caption, color = c.inkMuted)
    }
}

/** Icon and tone for a place kind (same as its map marker). */
fun placeIcon(kind: Int, c: com.lifeline.mesh.ui.theme.LifelineColors): Pair<Int, androidx.compose.ui.graphics.Color> = when (kind) {
    0 -> R.drawable.ic_verified_user to c.safe
    1 -> R.drawable.ic_block to c.warn
    2 -> R.drawable.ic_water_drop to c.link
    3 -> R.drawable.ic_groups to c.safe
    4 -> R.drawable.ic_groups to c.warn
    11 -> R.drawable.ic_person to c.sos
    12 -> R.drawable.ic_volunteer_activism to c.warn
    14 -> R.drawable.ic_local_fire_department to c.link
    15 -> R.drawable.ic_emergency to c.link
    16 -> R.drawable.ic_local_police to c.link
    17 -> R.drawable.ic_boat to c.link
    else -> R.drawable.ic_health_and_safety to c.link
}

private fun reportLabel(kind: Int) = when (kind) { 1 -> R.string.report_1; 2 -> R.string.report_2; 3 -> R.string.report_3; else -> R.string.report_4 }
