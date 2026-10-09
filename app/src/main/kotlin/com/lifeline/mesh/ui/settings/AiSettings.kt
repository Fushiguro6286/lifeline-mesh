package com.lifeline.mesh.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.ai.AiStatus
import com.lifeline.mesh.ai.SelectorStage
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.components.SecondaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.Touch
import kotlinx.coroutines.launch

/**
 * Settings → On-device AI (docs/MODEL_SELECTOR.md section 7): the phone, the chosen model with measured speed and
 * per-language results, status, Re-test / Import / Download / Remove, and "Why this model?".
 * English only, like the rest of Settings (DESIGN.md 9).
 */
@Composable
fun AiSettingsCard(onBenchmarks: () -> Unit) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val ai = (context.applicationContext as LifelineApp).ai
    val status by ai.status.collectAsStateWithLifecycle()
    val sel by ai.selection.collectAsStateWithLifecycle()
    val device by ai.device.collectAsStateWithLifecycle()
    val installed by ai.installed.collectAsStateWithLifecycle()
    val gov by ai.governor.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var why by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            message = null
            val r = ai.import(uri) { done, total -> progress = "Importing" to (done.toFloat() / total.coerceAtLeast(1)) }
            progress = null
            message = r.fold({ "Installed ${it.name}. Testing it now." }, { it.message ?: "Import failed" })
        }
    }

    Column(
        Modifier.fillMaxWidth().background(c.surface, Shapes.card).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        device?.let { d ->
            Text(
                "${d.phone} · ${d.chip} · ${"%.0f".format(d.profile.totalRamMb / 1000.0)} GB RAM · ${d.profile.freeStorageMb / 1000} GB free",
                style = Lifeline.type.caption, color = c.inkMuted,
            )
        }
        val statusText = when (val s = status) {
            AiStatus.Idle -> "Not started"
            is AiStatus.Loading -> "Loading ${s.model} on ${s.backend}…"
            is AiStatus.Testing -> "Testing ${s.model} on ${s.backend}: " + when (s.stage) {
                SelectorStage.LOADING -> "loading"; SelectorStage.SPEED_PROBE -> "speed test"; SelectorStage.LANGUAGE_PROBE -> "language test"
            }
            is AiStatus.Ready -> if (gov.pauseNonEssential) "Ready · extras paused (${gov.reason})" else "Ready"
            is AiStatus.RulesOnly -> "Rules only" + (s.reason?.let { " · $it" } ?: "")
        }
        Text(statusText, style = Lifeline.type.bodyStrong, color = if (status is AiStatus.Ready) c.safe else c.ink)

        sel?.let { s ->
            if (s.chosenName != null) {
                Text("${s.chosenName} on ${s.backend}", style = Lifeline.type.title, color = c.ink)
                Text(
                    "Measured: first token ${s.ttftMs?.let { "%.1f s".format(it / 1000.0) } ?: "—"} · " +
                        "${s.decodeTokPerSec?.let { "%.1f tok/s".format(it) } ?: "—"}",
                    style = Lifeline.type.body, color = c.ink,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    listOf("en" to "English", "hi" to "Hindi", "kn" to "Kannada").forEach { (tag, name) ->
                        val ok = s.languages[tag] == true
                        Text("$name ${if (ok) "✓" else "✗ (rules)"}", style = Lifeline.type.label, color = if (ok) c.safe else c.warn)
                    }
                }
            } else if (status !is AiStatus.Testing && status !is AiStatus.Loading) {
                Text("No model passed on this phone, so every AI feature uses its rule-based fallback.", style = Lifeline.type.body, color = c.inkMuted)
            }
            s.betterName?.let { name ->
                Text(
                    "Better AI available for this phone: $name (${"%.1f".format((s.betterSizeBytes ?: 0) / 1e9)} GB)",
                    style = Lifeline.type.body, color = c.link,
                )
            }
        }

        progress?.let { (what, f) ->
            Text("$what… ${(f * 100).toInt()}%", style = Lifeline.type.caption, color = c.inkMuted)
            LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = c.link, trackColor = c.surfaceRaised)
        }
        message?.let { Text(it, style = Lifeline.type.body, color = c.ink) }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            PrimaryButton("Re-test", { message = null; ai.warmUp(force = true) }, enabled = status !is AiStatus.Testing && progress == null)
            SecondaryButton("Import model file", { picker.launch(arrayOf("*/*")) })
            val downloadable = ai.catalog.firstOrNull { it.id == sel?.betterId && sel?.betterDownloadable == true }
                ?: ai.catalog.firstOrNull { it.id !in installed && !it.gated && it.downloadUrl != null && sel?.chosenId == null }
            if (downloadable != null && progress == null) {
                SecondaryButton("Download ${downloadable.name} (${"%.1f".format(downloadable.sizeBytes / 1e9)} GB, Wi-Fi)", {
                    scope.launch {
                        message = null
                        val r = ai.download(downloadable) { done, total -> progress = "Downloading" to (done.toFloat() / total.coerceAtLeast(1)) }
                        progress = null
                        message = r.fold({ "Downloaded ${it.name}. Testing it now." }, { it.message ?: "Download failed" })
                    }
                })
            }
            sel?.chosenId?.let { id -> ai.catalog.firstOrNull { it.id == id } }?.let { m ->
                SecondaryButton("Remove ${m.name}", { ai.remove(m); message = "Removed ${m.name}." })
            }
            SecondaryButton("Benchmarks", onBenchmarks)
        }

        Text(
            (if (why) "▾ " else "▸ ") + "Why this model?",
            style = Lifeline.type.bodyStrong, color = c.link,
            modifier = Modifier.heightIn(min = Touch.standard).clickable(role = Role.Button) { why = !why }.padding(vertical = Space.s)
                .semantics { heading() },
        )
        if (why) sel?.let { s ->
            s.attempts.forEach { a ->
                Text(
                    "${a.modelName} · ${a.backend} · ${a.outcome}" +
                        (if (a.outcome == "Chosen" && a.ttftMs != null) " (TTFT %.1f s, %.0f tok/s)".format(a.ttftMs / 1000.0, a.decodeTokPerSec ?: 0.0) else ""),
                    style = Lifeline.type.caption, color = if (a.outcome == "Chosen") c.safe else c.ink,
                )
            }
            s.rejected.forEach { Text(it, style = Lifeline.type.caption, color = c.inkMuted) }
            Text("Installed: " + (installed.keys.joinToString().ifBlank { "none" }) + " · folder ${ai.installer.externalDir.path}",
                style = Lifeline.type.caption, color = c.inkMuted)
        }
    }
}
