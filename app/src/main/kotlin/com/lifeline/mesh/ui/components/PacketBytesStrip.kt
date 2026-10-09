package com.lifeline.mesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.LifelineColors
import com.lifeline.mesh.ui.theme.PreviewBox
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.ThemePreviews

/** A named byte range inside an encoded packet. */
data class PacketField(val name: String, val start: Int, val length: Int) {
    val endExclusive get() = start + length
}

/** Field tints cycle through the semantic hues, so neighboring fields never share a tint. */
private fun LifelineColors.fieldTint(index: Int): Color =
    listOf(signal, link, safe, warn, sosInk, inkMuted)[index % 6]

/**
 * The encoded packet as a colored hex strip, one tint per field, with a legend.
 * Tapping a byte or a legend entry highlights that field and shows its name and byte range.
 */
@Composable
fun PacketBytesStrip(bytes: ByteArray, fields: List<PacketField>, modifier: Modifier = Modifier) {
    val c = Lifeline.colors
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    fun fieldOf(byteIndex: Int) = fields.indexOfFirst { byteIndex >= it.start && byteIndex < it.endExclusive }
    val bytesLabel = stringResource(R.string.bytes_label)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(bytes.size.toString(), style = Lifeline.type.display, color = c.ink)
            Text(bytesLabel, style = Lifeline.type.label, color = c.inkMuted, modifier = Modifier.padding(bottom = 8.dp))
        }

        FlowRow(
            Modifier.clearAndSetSemantics { contentDescription = "${bytes.size} $bytesLabel" },
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            bytes.forEachIndexed { i, b ->
                val f = fieldOf(i)
                val tint = if (f >= 0) c.fieldTint(f) else c.outline
                val dim = selected != null && selected != f
                Box(
                    Modifier
                        .size(width = 30.dp, height = 26.dp)
                        .clip(Shapes.chip)
                        .background(tint.copy(alpha = if (dim) 0.06f else 0.22f))
                        .then(if (selected == f && f >= 0) Modifier.border(1.5.dp, tint, Shapes.chip) else Modifier)
                        .clickable { selected = if (selected == f || f < 0) null else f },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("%02X".format(b.toInt() and 0xFF), style = Lifeline.type.caption.copy(fontFamily = FontFamily.Monospace),
                        color = if (dim) c.inkMuted else c.ink)
                }
            }
        }

        selected?.let { idx ->
            val f = fields[idx]
            val range = if (f.length == 1) "byte ${f.start}" else "bytes ${f.start}–${f.endExclusive - 1}"
            Text("${f.name} · $range", style = Lifeline.type.bodyStrong, color = c.ink)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            fields.forEachIndexed { i, f ->
                Row(
                    Modifier
                        .heightIn(min = 32.dp)
                        .clip(Shapes.chip)
                        .background(if (selected == i) c.surfaceRaised else Color.Transparent)
                        .clickable(role = Role.Button) { selected = if (selected == i) null else i }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(10.dp).background(c.fieldTint(i), Shapes.chip))
                    Text(f.name, style = Lifeline.type.caption, color = c.ink, textAlign = TextAlign.Start)
                }
            }
        }
    }
}

@ThemePreviews
@Composable
private fun PacketBytesStripPreview() = PreviewBox {
    // Placeholder bytes laid out like an Alert header + body (real encoding arrives in Phase 1).
    val bytes = ByteArray(40) { (it * 37 + 11).toByte() }
    PacketBytesStrip(bytes, sampleAlertFields(noteBytes = 9))
}

/** Field layout of an Alert packet (CLAUDE.md section 6), used for placeholder data in previews and the gallery. */
fun sampleAlertFields(noteBytes: Int) = listOf(
    PacketField("Version · type", 0, 1),
    PacketField("Message ID", 1, 8),
    PacketField("Origin", 9, 4),
    PacketField("TTL", 13, 1),
    PacketField("Hops", 14, 1),
    PacketField("Time", 15, 4),
    PacketField("Latitude", 19, 4),
    PacketField("Longitude", 23, 4),
    PacketField("Accuracy", 27, 1),
    PacketField("Needs", 28, 1),
    PacketField("Severity", 29, 1),
    PacketField("People", 30, 1),
    PacketField("Note", 31, noteBytes),
)
