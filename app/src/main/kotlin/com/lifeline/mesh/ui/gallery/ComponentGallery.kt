package com.lifeline.mesh.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.data.ThemeMode
import com.lifeline.mesh.packet.Need
import com.lifeline.mesh.ui.components.BigIconButton
import com.lifeline.mesh.ui.components.EmptyState
import com.lifeline.mesh.ui.components.HoldToSendButton
import com.lifeline.mesh.ui.components.NeedChip
import com.lifeline.mesh.ui.components.PacketBytesStrip
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.components.SeverityBadge
import com.lifeline.mesh.ui.components.SignalRings
import com.lifeline.mesh.ui.components.SimulatedBanner
import com.lifeline.mesh.ui.components.StatTile
import com.lifeline.mesh.ui.components.StatusItem
import com.lifeline.mesh.ui.components.StatusStrip
import com.lifeline.mesh.ui.components.StatusTimeline
import com.lifeline.mesh.ui.components.StepState
import com.lifeline.mesh.ui.components.TimelineStep
import com.lifeline.mesh.ui.components.sampleAlertFields
import com.lifeline.mesh.ui.onboarding.OptionCard
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.LifelineTheme
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space

/**
 * Debug screen: every shared component from DESIGN.md section 6 in Night or Daylight, with placeholder data.
 * Placeholder data lives only here and in @Preview functions.
 */
@Composable
fun ComponentGallery() {
    var theme by rememberSaveable { mutableStateOf(ThemeMode.NIGHT) }
    LifelineTheme(theme) {
        val c = Lifeline.colors
        Column(
            Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(Space.screen),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                OptionCard(stringResource(R.string.theme_night), theme == ThemeMode.NIGHT, { theme = ThemeMode.NIGHT }, Modifier.weight(1f))
                OptionCard(stringResource(R.string.theme_daylight), theme == ThemeMode.DAYLIGHT, { theme = ThemeMode.DAYLIGHT }, Modifier.weight(1f))
            }

            Heading("Color tokens")
            ColorSwatches()

            Heading("Type")
            Text("12", style = Lifeline.type.display, color = c.ink)
            Text("Headline · Archivo condensed", style = Lifeline.type.headline, color = c.ink)
            Text("Title · Archivo", style = Lifeline.type.title, color = c.ink)
            Text("Body · Atkinson Hyperlegible, 17 sp for stressed readers.", style = Lifeline.type.body, color = c.ink)
            Text("Body strong", style = Lifeline.type.bodyStrong, color = c.ink)
            Text("Label", style = Lifeline.type.label, color = c.ink)
            Text("Caption", style = Lifeline.type.caption, color = c.inkMuted)
            Text("पानी बढ़ गया है, छत पर 5 लोग हैं", style = Lifeline.type.body, color = c.ink)
            Text("ಮನೆಯಲ್ಲಿ 4 ಜನ ಸಿಲುಕಿದ್ದಾರೆ, ನೀರು ಬೇಕು", style = Lifeline.type.body, color = c.ink)

            Heading("Status strip and Simulated banner")
            StatusStrip(StatusItem("3 phones nearby", c.link), StatusItem("AI ready", c.safe), simulated = false)
            StatusStrip(StatusItem("Searching…", c.warn, searching = true), StatusItem("Rules only", c.inkMuted), simulated = true)
            SimulatedBanner()

            Heading("SignalRings")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xxl), modifier = Modifier.padding(Space.xl)) {
                listOf(c.signal, c.link, c.sos, c.safe).forEach { tone ->
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        SignalRings(tone, active = true, modifier = Modifier.size(40.dp))
                        Box(Modifier.size(40.dp).background(c.surfaceRaised, Shapes.circle).border(2.dp, tone, Shapes.circle))
                    }
                }
            }

            Heading("HoldToSendButton")
            var sentCount by rememberSaveable { mutableIntStateOf(0) }
            Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                HoldToSendButton(onSend = { sentCount++ })
            }
            Text("Completed holds this session: $sentCount", style = Lifeline.type.caption, color = c.inkMuted)

            Heading("BigIconButton")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                BigIconButton(R.drawable.ic_mic, stringResource(R.string.speak), {}, Modifier.weight(1f))
                BigIconButton(R.drawable.ic_keyboard, stringResource(R.string.type), {}, Modifier.weight(1f))
            }

            Heading("NeedChip")
            var picked by rememberSaveable { mutableIntStateOf(Need.TRAPPED.bit or Need.MEDICAL.bit) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Need.selectable.forEach { n ->
                    NeedChip(n, picked and n.bit != 0, { on -> picked = if (on) picked or n.bit else picked and n.bit.inv() })
                }
            }

            Heading("SeverityBadge")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { (3 downTo 0).forEach { SeverityBadge(it) } }

            Heading("StatTile")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                StatTile("2", "Hops", Modifier.weight(1f))
                StatTile(null, "Round trip", Modifier.weight(1f))
                StatTile("91", "Packet", Modifier.weight(1f), unit = "B", simulated = true)
            }

            Heading("PacketBytesStrip")
            Text("Placeholder bytes; real encoding arrives in Phase 1.", style = Lifeline.type.caption, color = c.inkMuted)
            PacketBytesStrip(ByteArray(40) { (it * 37 + 11).toByte() }, sampleAlertFields(noteBytes = 9))

            Heading("StatusTimeline")
            StatusTimeline(
                listOf(
                    TimelineStep(stringResource(R.string.status_created), StepState.Done),
                    TimelineStep(stringResource(R.string.status_waiting), StepState.Current, stringResource(R.string.status_waiting_detail)),
                    TimelineStep(stringResource(R.string.status_help_coming), StepState.Pending),
                )
            )
            StatusTimeline(
                listOf(
                    TimelineStep(stringResource(R.string.status_created), StepState.Done),
                    TimelineStep(pluralStringResource(R.plurals.status_passed, 2, 2), StepState.Done),
                    TimelineStep(stringResource(R.string.status_help_coming), StepState.Current, "ETA 20 min", tone = c.safe),
                )
            )

            Heading("Buttons")
            PrimaryButton("Send alert", {}, Modifier.fillMaxWidth(), color = c.sos)
            PrimaryButton("Acknowledge", {}, Modifier.fillMaxWidth())
            SecondaryButton("Open app settings", {})

            Heading("EmptyState")
            EmptyState("No alerts yet", "Alerts from nearby phones appear here as soon as they arrive.", "Load sample alerts", {}, icon = R.drawable.ic_hub)
        }
    }
}

@Composable
private fun Heading(text: String) = Text(
    text, style = Lifeline.type.title, color = Lifeline.colors.ink,
    modifier = Modifier.padding(top = Space.l).semantics { heading() },
)

@Composable
private fun ColorSwatches() {
    val c = Lifeline.colors
    val tokens = listOf(
        "bg" to c.bg, "surface" to c.surface, "surfaceRaised" to c.surfaceRaised, "outline" to c.outline,
        "ink" to c.ink, "inkMuted" to c.inkMuted, "signal" to c.signal, "link" to c.link,
        "sos" to c.sos, "sosInk" to c.sosInk, "safe" to c.safe, "warn" to c.warn,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        tokens.forEach { (name, color) ->
            Column(Modifier.width(104.dp).background(c.surface, Shapes.card).padding(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Box(Modifier.fillMaxWidth().height(36.dp).background(color, Shapes.chip).border(1.dp, c.outline, Shapes.chip))
                Text(name, style = Lifeline.type.label, color = c.ink)
                Text(hex(color), style = Lifeline.type.caption, color = c.inkMuted)
                Text("on bg %.1f:1".format(contrast(color, c.bg)), style = Lifeline.type.caption, color = c.inkMuted)
            }
        }
    }
}

private fun hex(color: Color) = "#%06X".format((color.value shr 32).toLong() and 0xFFFFFF)

/** WCAG contrast ratio, shown live so the gallery doubles as a contrast check. */
private fun contrast(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
