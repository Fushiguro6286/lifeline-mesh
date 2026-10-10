package com.lifeline.mesh.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.ai.AiRuntime
import com.lifeline.mesh.data.MetricEntity
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * How it works (Demo mode only): the path of the last alert with this phone's own measurements, the On-device AI card
 * (model, why, GPU/CPU speed, languages) and the way to Benchmarks and the CSV export. Nothing here is typed in by hand:
 * a step without a measurement says "Not measured", and mesh numbers in simulation mode say "Simulated".
 */
@Composable
fun HowItWorksScreen(onBenchmarks: () -> Unit) {
    val c = Lifeline.colors
    val app = LocalContext.current.applicationContext as LifelineApp
    val settings by app.repository.settings.collectAsStateWithLifecycle()
    val simulated = settings?.simulationMode == true
    val mesh by app.repository.metrics.collectAsStateWithLifecycle()
    // LLM calls are always real, also in simulation mode, so they come from the real rows.
    val real by remember { app.database.metrics().observeReal() }.collectAsStateWithLifecycle(emptyList())
    val extract = remember(real) { real.mapNotNull { AiRuntime.decode(it) }.firstOrNull { it.feature == "extract" && it.ok } }

    fun latest(name: String, alertOnly: Boolean): MetricEntity? =
        mesh.firstOrNull { it.name == name && (!alertOnly || it.notes?.startsWith("alert") == true) }
    val tag = if (simulated) " · Simulated" else ""

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Box(Modifier.size(Space.s))
        Text("Panic in, clear alert out, even with no network.", style = Lifeline.type.headline, color = c.ink,
            modifier = Modifier.semantics { heading() })
        Text("Every number below was measured on this phone. Nothing is typed in by hand; a step that has not happened yet " +
            "says \"Not measured\".", style = Lifeline.type.body, color = c.inkMuted)

        Title("The last alert, step by step")
        Step(1, "Understood on the phone",
            extract?.let { "${app.ai.catalog.firstOrNull { m -> m.id == it.model }?.name ?: it.model} on ${it.backend} read the message in ${it.totalMs} ms" +
                (it.ttftMs?.let { t -> " (first token $t ms)" } ?: "") + ". No internet, no cloud." }
                ?: "Not measured yet. Speak or type an SOS; without a model the instant rules read it instead.")
        Step(2, "Packed small",
            latest("packet_bytes", alertOnly = true)?.let { "${it.value.toInt()} bytes for the whole alert: needs, people, severity, GPS and a note$tag." }
                ?: "Not measured")
        Step(3, "Passed phone to phone",
            latest("hops", alertOnly = true)?.let { "Arrived over ${it.value.toInt()} ${if (it.value.toInt() == 1) "hop" else "hops"}" +
                (if (simulated) " between the virtual phones of simulation mode · Simulated." else " over Bluetooth and Wi-Fi radios, no internet.") }
                ?: "Not measured")
        Step(4, "Round trip",
            latest("rtt_ms", alertOnly = false)?.let { "${it.value.toLong()} ms there and back, ${it.notes ?: ""}, timed on this phone's clock only$tag." }
                ?: "Not measured")
        Step(5, "Help is coming",
            "The rescue team's phone ranks alerts by need, people and distance (no AI in that maths) and sends " +
                "\"Help is coming\" back the same way.")

        Title("On-device AI")
        AiSettingsCard(onBenchmarks)

        Title("Measurements")
        ExportCard()
        Box(Modifier.size(Space.xl))
    }
}

@Composable
private fun Title(text: String) =
    Text(text, style = Lifeline.type.title, color = Lifeline.colors.ink, modifier = Modifier.padding(top = Space.s).semantics { heading() })

@Composable
private fun Step(n: Int, title: String, body: String) {
    val c = Lifeline.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface, Shapes.card).padding(Space.l),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(32.dp).background(c.link, Shapes.circle), contentAlignment = Alignment.Center) {
            Text("$n", style = Lifeline.type.bodyStrong, color = c.onFill, textAlign = TextAlign.Center)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Lifeline.type.bodyStrong, color = c.ink)
            Text(body, style = Lifeline.type.body, color = if (body == "Not measured") c.inkMuted else c.ink)
        }
    }
}

/** Export measurements (CLAUDE.md 10): CSV of every metric to app files, then the share sheet. */
@Composable
private fun ExportCard() {
    val c = Lifeline.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().background(c.surface, Shapes.card).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(result ?: "Every measurement as a CSV file, through the share sheet.", style = Lifeline.type.body, color = c.ink)
        SecondaryButton("Export measurements", {
            scope.launch {
                val out = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.lifeline.mesh.metrics.Metrics.export(context) }
                }
                out.onSuccess { r ->
                    if (r == null) result = "Nothing measured yet."
                    else { result = "Exported ${r.first} measurements."; context.startActivity(r.second) }
                }.onFailure { result = "Export failed: ${it.message}" }
            }
        }, Modifier.fillMaxWidth())
    }
}
