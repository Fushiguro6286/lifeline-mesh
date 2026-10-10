package com.lifeline.mesh.ui.mesh

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lifeline.mesh.R
import com.lifeline.mesh.data.MetricEntity
import com.lifeline.mesh.ui.components.StatTile
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Space

/**
 * Hops (last alert), round trip, packet bytes (last alert): the latest measured values, "Not measured" when none.
 * [metrics] are already limited to the current mode, so simulated and real numbers never mix; in simulation mode
 * every tile carries the Simulated tag.
 */
@Composable
fun Scoreboard(metrics: List<MetricEntity>, simulated: Boolean, modifier: Modifier = Modifier) {
    val hops = metrics.firstOrNull { it.name == "hops" && it.notes?.startsWith("alert") == true }
    val rtt = metrics.firstOrNull { it.name == "rtt_ms" }
    val bytes = metrics.firstOrNull { it.name == "packet_bytes" && it.notes?.startsWith("alert") == true }
    val range = metrics.filter { it.name == "link_range_m" }.maxByOrNull { it.value }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        StatTile(hops?.value?.toInt()?.toString(), stringResource(R.string.score_hops), Modifier.weight(1f).fillMaxHeight(), simulated = simulated)
        StatTile(rtt?.value?.toLong()?.toString(), stringResource(R.string.score_rtt), Modifier.weight(1f).fillMaxHeight(),
            unit = stringResource(R.string.unit_ms), simulated = simulated)
        StatTile(bytes?.value?.toInt()?.toString(), stringResource(R.string.score_bytes), Modifier.weight(1f).fillMaxHeight(), simulated = simulated)
    }
    // Farthest direct link: GPS distance to a phone heard over one link, with both fixes' uncertainty.
    val rangeText = range?.let { stringResource(R.string.score_range, it.value.toInt(), it.notes.orEmpty()) }
        ?: stringResource(R.string.score_range_none)
    Text(if (simulated) "$rangeText · ${stringResource(R.string.simulated_tag)}" else rangeText,
        style = Lifeline.type.caption, color = Lifeline.colors.inkMuted)
    }
}
