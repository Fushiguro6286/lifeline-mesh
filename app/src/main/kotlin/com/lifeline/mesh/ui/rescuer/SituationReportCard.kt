package com.lifeline.mesh.ui.rescuer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.R
import com.lifeline.mesh.ai.SituationFacts
import com.lifeline.mesh.ai.SituationReport
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Situation report card (DESIGN.md 8.4): three lines, "Written on device" when the model phrased them (every number
 * checked against the computed facts), refresh, time generated. The computed version shows at once and stays when the
 * model is missing, paused, slow or wrong. English, like the rest of the Rescue tab.
 */
@Composable
fun SituationReportCard(facts: SituationFacts) {
    val c = Lifeline.colors
    val app = LocalContext.current.applicationContext as LifelineApp
    val ai = app.ai
    val status by ai.status.collectAsStateWithLifecycle()
    var lines by remember { mutableStateOf(SituationReport.template(facts)) }
    var fromModel by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(System.currentTimeMillis()) }
    var refresh by remember { mutableIntStateOf(0) }

    // Recompute when any number changes (or on refresh); the model phrasing follows when it can.
    LaunchedEffect(facts, refresh, status) {
        lines = SituationReport.template(facts); fromModel = false; at = System.currentTimeMillis()
        if (!ai.canUse("en")) return@LaunchedEffect
        // GPS jitter can nudge the top alert's distance; wait for the numbers to settle before asking the model.
        delay(1_500)
        writing = true
        val phrased = try { ai.report(facts) } finally { writing = false }
        if (phrased != null) { lines = phrased; fromModel = true; at = System.currentTimeMillis() }
    }

    Column(
        Modifier.fillMaxWidth().background(c.surface, Shapes.card).border(1.dp, c.outline, Shapes.card).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Situation report", style = Lifeline.type.title, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = { app.readAloud.speak(lines.joinToString(" "), "en") }) {
                Icon(painterResource(R.drawable.ic_volume_up), "Read aloud", tint = c.link, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { refresh++ }, enabled = !writing) {
                Icon(painterResource(R.drawable.ic_refresh), "Refresh", tint = if (writing) c.inkMuted else c.link, modifier = Modifier.size(22.dp))
            }
        }
        lines.forEach { Text(it, style = Lifeline.type.body, color = c.ink) }
        Text(
            listOfNotNull(
                if (fromModel) "Written on device" else null,
                if (writing) "Writing on device…" else null,
                "at " + SimpleDateFormat("HH:mm", Locale.US).format(Date(at)),
            ).joinToString(" · "),
            style = Lifeline.type.caption, color = c.inkMuted,
        )
    }
}
