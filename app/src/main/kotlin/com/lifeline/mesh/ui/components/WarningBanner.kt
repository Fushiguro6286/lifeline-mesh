package com.lifeline.mesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.data.PacketEntity
import com.lifeline.mesh.data.warning
import com.lifeline.mesh.mesh.Router
import com.lifeline.mesh.packet.PacketType
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.delay

/**
 * Official warnings in force (Severe or Extreme, not expired), newest first, on the SOS and Rescue tabs. Each says
 * where it came from and how it reached this phone: from the server, or passed on by nearby phones, which no phone can
 * verify. A drill says so in its title.
 */
@Composable
fun WarningBanner(packets: List<PacketEntity>, modifier: Modifier = Modifier) {
    val c = Lifeline.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val inForce = packets.asSequence()
        .filter { it.type == PacketType.WARNING.code }
        .mapNotNull { e -> e.warning()?.let { e to it } }
        .filter { (_, w) -> w.level >= 2 && w.expires * 1000 > now }
        .sortedByDescending { (e, _) -> e.sentAt }
        .toList()
    if (inForce.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        inForce.forEach { (e, w) ->
            Column(
                Modifier.fillMaxWidth().background(c.surface, Shapes.card).border(2.dp, c.sos, Shapes.card).padding(Space.l)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    Icon(painterResource(R.drawable.ic_warning), null, tint = c.sos, modifier = Modifier.size(24.dp))
                    Text(stringResource(when { w.drill -> R.string.warning_title_drill; w.forecast -> R.string.warning_title_forecast
                        else -> R.string.warning_title }, w.event.ifBlank { "—" }) +
                        if (e.simulated) " · " + stringResource(R.string.simulated_tag) else "",
                        style = Lifeline.type.title, color = c.sos)
                }
                Text(w.headline, style = Lifeline.type.body, color = c.ink)
                val until = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(w.expires * 1000))
                val how = if (e.fromEndpoint == Router.INTERNET) stringResource(R.string.warning_via_internet)
                else stringResource(R.string.warning_via_mesh)
                // A drill is not from NDMA: only real warnings name the source; a forecast check says it is not official.
                val source = when { w.drill -> ""; w.forecast -> stringResource(R.string.warning_source_forecast) + " · "
                    else -> stringResource(R.string.warning_source) + " · " }
                // Simulated (simulation mode): no real source or route to name; the title already says Simulated.
                Text(if (e.simulated) stringResource(R.string.warning_until, until) else "$source${stringResource(R.string.warning_until, until)} · $how",
                    style = Lifeline.type.caption, color = c.inkMuted)
            }
        }
    }
}
