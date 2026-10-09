package com.lifeline.mesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.PreviewBox
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.ThemePreviews

/**
 * Big number with a small label. [value] null means nothing was measured: shows "Not measured".
 * [simulated] adds a "Simulated" tag; simulated numbers are never shown without it.
 */
@Composable
fun StatTile(
    value: String?,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    simulated: Boolean = false,
) {
    val c = Lifeline.colors
    val notMeasured = stringResource(R.string.not_measured)
    val simulatedTag = stringResource(R.string.simulated_tag)
    val spoken = buildString {
        append(label).append(": ")
        append(value?.let { if (unit != null) "$it $unit" else it } ?: notMeasured)
        if (simulated) append(", ").append(simulatedTag)
    }
    Column(
        modifier
            .heightIn(min = 96.dp)
            .background(c.surface, Shapes.card)
            .padding(Space.m)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (value == null) {
            // A dash keeps the number line the same height as its neighbours; the words go with the label, where
            // narrow tiles and large font scales can wrap them between words instead of mid-word.
            Text("—", style = Lifeline.type.display, color = c.inkMuted, maxLines = 1)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = Lifeline.type.display, color = c.ink, maxLines = 1)
                if (unit != null) {
                    Text(unit, style = Lifeline.type.label, color = c.inkMuted, modifier = Modifier.padding(start = Space.xs, bottom = 6.dp))
                }
            }
        }
        // FlowRow: in narrow tiles the Simulated tag drops below the label instead of squeezing it.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = Lifeline.type.caption, color = c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (value == null) Text(notMeasured, style = Lifeline.type.caption, color = c.inkMuted)
            if (simulated) {
                Text(simulatedTag, style = Lifeline.type.caption, color = c.warn,
                    modifier = Modifier.border(1.dp, c.warn, Shapes.chip).padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
    }
}

@ThemePreviews
@Composable
private fun StatTilePreview() = PreviewBox {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        StatTile("2", "Hops", Modifier.width(110.dp))
        StatTile(null, "Round trip", Modifier.width(110.dp))
        StatTile("91", "Packet", Modifier.width(110.dp), unit = "B", simulated = true)
    }
}
