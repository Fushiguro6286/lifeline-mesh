package com.lifeline.mesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.PreviewBox
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.ThemePreviews

/** Red strip shown on every screen while simulation mode is on. */
@Composable
fun SimulatedBanner(modifier: Modifier = Modifier) {
    val c = Lifeline.colors
    Text(
        stringResource(R.string.simulated_banner),
        style = Lifeline.type.label,
        color = c.onFill,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().background(c.sos).padding(vertical = 6.dp, horizontal = Space.l)
            .semantics { heading() },
    )
}

/** One status item: a dot (or scanning rings while [searching]) and a short text. */
data class StatusItem(val text: String, val tone: Color, val searching: Boolean = false)

/**
 * Thin strip at the top of every screen: mesh state on the left, AI state on the right,
 * and the Simulated banner under it when simulation mode is on.
 */
@Composable
fun StatusStrip(
    mesh: StatusItem,
    ai: StatusItem,
    simulated: Boolean,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = Space.screen, end = if (trailing == null) Space.screen else Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            StatusDot(mesh, Modifier.weight(1f))
            StatusDot(ai)
            trailing?.invoke()
        }
        if (simulated) SimulatedBanner()
    }
}

@Composable
private fun StatusDot(item: StatusItem, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
            SignalRings(item.tone, active = item.searching, modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
            Box(Modifier.size(8.dp).background(item.tone, androidx.compose.foundation.shape.CircleShape))
        }
        Text(item.text, style = Lifeline.type.label, color = Lifeline.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@ThemePreviews
@Composable
private fun StatusStripPreview() = PreviewBox {
    val c = Lifeline.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        StatusStrip(StatusItem("3 phones nearby", c.link), StatusItem("AI ready", c.safe), simulated = false)
        StatusStrip(StatusItem("Searching…", c.warn, searching = true), StatusItem("Rules only", c.inkMuted), simulated = true)
    }
}
