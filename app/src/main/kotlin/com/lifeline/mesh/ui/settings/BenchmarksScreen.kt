package com.lifeline.mesh.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.ai.AiRuntime
import com.lifeline.mesh.ai.AiStatus
import com.lifeline.mesh.ai.AlertDraft
import com.lifeline.mesh.ai.LlmBackend
import com.lifeline.mesh.data.MetricEntity
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Benchmarks (DESIGN.md 8.6). Everything here is measured on this phone; nothing measured shows "Not measured".
 * Simulated metrics are never shown here. LLM timings are the app's own stopwatch: first streamed token, and decode
 * rate over the tokens after the first (LiteRT-LM 0.18 streams one token per chunk).
 */
@Composable
fun BenchmarksScreen() {
    val c = Lifeline.colors
    val context = LocalContext.current
    val app = context.applicationContext as LifelineApp
    val ai = app.ai
    val metrics by remember { app.database.metrics().observeReal() }.collectAsStateWithLifecycle(emptyList())
    val status by ai.status.collectAsStateWithLifecycle()
    val sel by ai.selection.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var demo by remember { mutableStateOf<List<AiRuntime.DemoResult>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    val calls = remember(metrics) { metrics.mapNotNull { AiRuntime.decode(it) } }
    val probes = remember(metrics) { metrics.filter { it.name == "probe" } }
    val bench = calls.filter { it.feature == "benchmark" && it.ok }
    fun avg(b: String, f: (com.lifeline.mesh.ai.LlmCallRecord) -> Double?) =
        bench.filter { it.backend == b }.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = Space.screen), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        item { Spacer() }

        // ---- Demo sentences
        item { Heading("Demo sentences") }
        item {
            Card {
                Text("The four demo sentences through the rules and the on-device AI, against the expected answer.",
                    style = Lifeline.type.caption, color = c.inkMuted)
                PrimaryButton(if (busy == "demo") "Running…" else "Test demo sentences", {
                    busy = "demo"
                    scope.launch { demo = ai.testDemoSentences(); busy = null }
                }, enabled = busy == null && status is AiStatus.Ready)
                if (status !is AiStatus.Ready) Text("Needs a loaded model (see Settings → On-device AI).", style = Lifeline.type.caption, color = c.warn)
                demo?.forEach { d ->
                    Column(Modifier.fillMaxWidth().background(c.surfaceRaised, Shapes.card).padding(Space.m), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("“${d.text}”", style = Lifeline.type.body, color = c.ink)
                        Text("Expected: ${fmt(d.expected)}", style = Lifeline.type.caption, color = c.inkMuted)
                        Text("Rules: ${fmt(d.rules)} ${mark(d.rulesCorrect)}", style = Lifeline.type.caption, color = if (d.rulesCorrect) c.safe else c.warn)
                        Text(
                            "On-device AI: " + (if (!d.usedAi) "not used for this language (rules)" else d.ai?.let { "${fmt(it)} ${mark(d.aiCorrect)}" } ?: "no valid answer (rules used)"),
                            style = Lifeline.type.caption, color = if (d.aiCorrect) c.safe else c.warn,
                        )
                    }
                }
            }
        }

        // ---- GPU vs CPU
        item { Heading("GPU vs CPU") }
        item {
            Card {
                Text("Model: ${sel?.chosenName ?: "none chosen"} · fixed prompt, 32 tokens, 5 runs per backend.",
                    style = Lifeline.type.caption, color = c.inkMuted)
                val gpuT = avg("GPU") { it.ttftMs?.toDouble() }; val cpuT = avg("CPU") { it.ttftMs?.toDouble() }
                val gpuS = avg("GPU") { it.tokPerSec }; val cpuS = avg("CPU") { it.tokPerSec }
                Bars("First token (ms, lower is better)", listOf("GPU" to gpuT, "CPU" to cpuT), c.link)
                Bars("Decode (tokens/s, higher is better)", listOf("GPU" to gpuS, "CPU" to cpuS), c.safe)
                Text("NPU: not tried. It needs a .litertlm built for this exact chip (${ai.device.value?.chip ?: "?"}) and the NPU libraries " +
                    "bundled; none is installed. Hugging Face lists NPU builds for other Snapdragon chips only.",
                    style = Lifeline.type.caption, color = c.inkMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf(LlmBackend.GPU, LlmBackend.CPU).forEach { b ->
                        SecondaryButton(if (busy == b.name) "Running on $b…" else "Run benchmark on $b", {
                            busy = b.name; note = null
                            ai.benchmark(b) { err -> busy = null; note = err }
                        })
                    }
                }
                note?.let { Text(it, style = Lifeline.type.body, color = c.warn) }
            }
        }

        // ---- Calls
        item { Heading("LLM calls (${calls.size})") }
        if (calls.isEmpty()) item { Muted("Not measured yet.") }
        items(calls.take(40), key = { "c${it.at}${it.feature}" }) { r ->
            Text(
                "${time(r.at)} · ${r.feature} · ${r.model} · ${r.backend} · TTFT ${r.ttftMs?.let { "$it ms" } ?: "—"} · " +
                    "${r.tokPerSec?.let { "%.1f tok/s".format(it) } ?: "—"} · prompt ${r.promptTokens ?: "—"} · out ${r.outputTokens ?: "—"} · " +
                    "total ${r.totalMs} ms${if (r.ok) "" else " · fell back"}",
                style = Lifeline.type.caption, color = if (r.ok) c.ink else c.warn,
            )
        }

        // ---- Probes
        item { Heading("Model probes (${probes.size})") }
        if (probes.isEmpty()) item { Muted("Not measured yet.") }
        items(probes.take(20), key = { "p${it.rowId}" }) { m ->
            Text("${time(m.timestamp)} · ${m.backend} · TTFT ${m.value.toLong()} ms · ${m.notes}", style = Lifeline.type.caption, color = c.ink)
        }

        // ---- Mesh
        item { Heading("Mesh (real phones only)") }
        item {
            Card {
                MetricLine("Packet sizes", metrics.filter { it.name == "packet_bytes" }, "bytes")
                MetricLine("Hops on arrival", metrics.filter { it.name == "hops" }, "hops")
                MetricLine("Round trips", metrics.filter { it.name == "rtt_ms" }, "ms")
                Text("Battery drain: Not measured (Phase 9).", style = Lifeline.type.body, color = c.inkMuted)
            }
        }
        item { Spacer() }
    }
}

@Composable
private fun MetricLine(label: String, rows: List<MetricEntity>, unit: String) {
    val c = Lifeline.colors
    if (rows.isEmpty()) {
        Text("$label: Not measured", style = Lifeline.type.body, color = c.inkMuted)
    } else {
        val v = rows.map { it.value }
        Text("$label: ${rows.size} measured · last ${v.first().toLong()} $unit · min ${v.min().toLong()} · max ${v.max().toLong()}",
            style = Lifeline.type.body, color = c.ink)
    }
}

/** Horizontal bars for two values; a missing value says "Not measured". */
@Composable
private fun Bars(title: String, values: List<Pair<String, Double?>>, tone: Color) {
    val c = Lifeline.colors
    val max = values.mapNotNull { it.second }.maxOrNull() ?: 1.0
    Text(title, style = Lifeline.type.label, color = c.ink)
    values.forEach { (name, v) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(name, style = Lifeline.type.label, color = c.ink, modifier = Modifier.width(40.dp))
            if (v == null) Text("Not measured", style = Lifeline.type.caption, color = c.inkMuted)
            else {
                Box(Modifier.weight(1f).height(18.dp)) {
                    Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0.03f, 1f)).fillMaxHeight().background(tone, Shapes.chip))
                }
                Text("%.0f".format(v), style = Lifeline.type.label, color = c.ink, modifier = Modifier.width(56.dp))
            }
        }
    }
}

private fun fmt(d: AlertDraft) = "${d.needs.joinToString("+") { it.label }.ifBlank { "none" }}, ${d.people} people, sev ${d.severity}"
private fun mark(ok: Boolean) = if (ok) "✓" else "✗"
private fun time(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

@Composable private fun Spacer() = androidx.compose.foundation.layout.Spacer(Modifier.height(Space.s))
@Composable private fun Heading(t: String) = Text(t, style = Lifeline.type.title, color = Lifeline.colors.ink, modifier = Modifier.semantics { heading() })
@Composable private fun Muted(t: String) = Text(t, style = Lifeline.type.body, color = Lifeline.colors.inkMuted)
@Composable private fun Card(content: @Composable () -> Unit) = Column(
    Modifier.fillMaxWidth().background(Lifeline.colors.surface, Shapes.card).padding(Space.l),
    verticalArrangement = Arrangement.spacedBy(Space.s),
) { content() }
