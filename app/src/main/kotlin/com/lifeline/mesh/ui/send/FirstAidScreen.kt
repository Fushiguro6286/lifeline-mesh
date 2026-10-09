package com.lifeline.mesh.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.R
import com.lifeline.mesh.ai.FirstAid
import com.lifeline.mesh.ai.FirstAidEntry
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.Touch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screen shows for a question: an entry (and who picked it), or "no matching guide". */
private sealed interface Pick {
    data class Found(val entry: FirstAidEntry, val byModel: Boolean) : Pick
    data object NoMatch : Pick
}

/**
 * First aid (CLAUDE.md 9.7). Vetted guides from assets/firstaid.json, shown word for word with their source, always
 * under "Contact emergency services if you can." A question is matched to a guide by the on-device model (ids only)
 * or by keywords; every guide can also be opened directly. Without the file the screen says the guides are missing.
 */
@Composable
fun FirstAidScreen() {
    val c = Lifeline.colors
    val context = LocalContext.current
    val ai = (context.applicationContext as LifelineApp).ai
    val scope = rememberCoroutineScope()
    // null while loading; empty when the file is missing or has no valid entry.
    val entries by produceState<List<FirstAidEntry>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("firstaid.json").bufferedReader().use { FirstAid.parse(it.readText()) } }.getOrDefault(emptyList())
        }
    }
    var question by rememberSaveable { mutableStateOf("") }
    var pick by remember { mutableStateOf<Pick?>(null) }
    var finding by remember { mutableStateOf(false) }

    fun find(list: List<FirstAidEntry>) {
        val q = question.trim().ifBlank { return }
        finding = true
        scope.launch {
            val id = ai.firstAid(q, list)
            val byModel = id != null && id != FirstAid.NONE
            val entry = if (byModel) list.first { it.id == id } else FirstAid.ruleMatch(q, list)
            pick = entry?.let { Pick.Found(it, byModel) } ?: Pick.NoMatch
            finding = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).imePadding(),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Spacer(Modifier.size(Space.s))
        // Always first, always visible.
        Row(
            Modifier.fillMaxWidth().background(c.surface, Shapes.card).border(1.dp, c.warn, Shapes.card).padding(Space.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Icon(painterResource(R.drawable.ic_local_hospital), null, tint = c.warn, modifier = Modifier.size(28.dp))
            Text(stringResource(R.string.firstaid_contact), style = Lifeline.type.bodyStrong, color = c.ink)
        }

        var ambulance by rememberSaveable { mutableStateOf(false) }
        AmbulanceCard({ ambulance = true })
        if (ambulance) AmbulanceSheet(onDismiss = { ambulance = false })

        val list = entries
        when {
            list == null -> Unit
            list.isEmpty() -> Text(stringResource(R.string.firstaid_missing), style = Lifeline.type.body, color = c.inkMuted)
            else -> {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(300); pick = null },
                    label = { Text(stringResource(R.string.firstaid_ask)) },
                    textStyle = Lifeline.type.body,
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(
                    stringResource(if (finding) R.string.firstaid_finding else R.string.firstaid_find),
                    { find(list) }, Modifier.fillMaxWidth(), enabled = question.isNotBlank() && !finding,
                )
                when (val p = pick) {
                    is Pick.Found -> EntryCard(p.entry, stringResource(if (p.byModel) R.string.firstaid_by_ai else R.string.firstaid_by_keywords))
                    Pick.NoMatch -> Text(stringResource(R.string.firstaid_none), style = Lifeline.type.bodyStrong, color = c.warn)
                    null -> Unit
                }
                Text(stringResource(R.string.firstaid_all), style = Lifeline.type.title, color = c.ink,
                    modifier = Modifier.padding(top = Space.s).semantics { heading() })
                list.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Touch.standard).background(c.surface, Shapes.card)
                            .clickable(role = Role.Button) { pick = Pick.Found(e, byModel = false); question = "" }.padding(Space.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(e.title, style = Lifeline.type.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = c.inkMuted)
                    }
                }
            }
        }
        Spacer(Modifier.size(Space.xl))
    }
}

/** The guide word for word, its source, and the emergency line again under it. */
@Composable
private fun EntryCard(e: FirstAidEntry, pickedBy: String) {
    val c = Lifeline.colors
    Column(
        Modifier.fillMaxWidth().background(c.surfaceRaised, Shapes.card).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(e.title, style = Lifeline.type.title, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(e.text, style = Lifeline.type.body, color = c.ink)
        Text(stringResource(R.string.firstaid_source, e.source), style = Lifeline.type.caption, color = c.inkMuted)
        Text(stringResource(R.string.firstaid_contact), style = Lifeline.type.bodyStrong, color = c.warn)
        Text(pickedBy, style = Lifeline.type.caption, color = c.inkMuted)
    }
}
