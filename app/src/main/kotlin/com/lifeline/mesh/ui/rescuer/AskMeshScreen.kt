package com.lifeline.mesh.ui.rescuer

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.ai.AskMesh
import com.lifeline.mesh.ai.MeshAnswer
import com.lifeline.mesh.triage.RankedAlert
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.Touch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One question and its answer; [answer] null while the model is reading. [alerts] = how many alerts it was asked about. */
data class Exchange(val id: Long, val question: String, val answer: MeshAnswer?, val alerts: Int)

class AskViewModel(app: Application) : AndroidViewModel(app) {
    private val ai = (app as LifelineApp).ai
    private val _chat = MutableStateFlow<List<Exchange>>(emptyList())
    val chat: StateFlow<List<Exchange>> = _chat

    /** Model answer if it cites real alerts; otherwise (or with no model) the rule-based answer (CLAUDE.md 9.5). */
    fun ask(question: String, ranked: List<RankedAlert>) {
        val q = question.trim().ifBlank { return }
        val id = System.nanoTime()
        _chat.update { it + Exchange(id, q, null, ranked.size) }
        viewModelScope.launch {
            val a = (if (ranked.isNotEmpty() && ai.canUse("en")) ai.ask(q, ranked) else null) ?: AskMesh.ruleAnswer(q, ranked)
            _chat.update { list -> list.map { if (it.id == id) it.copy(answer = a) else it } }
        }
    }
}

/**
 * Ask the mesh (DESIGN.md 8.4): chat with quick questions; answers cite alerts as tappable #n chips that open the alert.
 * "Answered from N stored alerts" when the model answered, "Rules" when the rule-based answer was used.
 */
@Composable
fun AskMeshScreen(vm: AskViewModel = viewModel(), loc: RescuerViewModel = viewModel()) {
    val c = Lifeline.colors
    val repo = (LocalContext.current.applicationContext as LifelineApp).repository
    val packets by repo.packets.collectAsStateWithLifecycle()
    val me by loc.me.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val ranked = remember(packets, me) { rankAlerts(packets, me, System.currentTimeMillis()).also { now = System.currentTimeMillis() } }
    var input by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(chat.size, chat.lastOrNull()?.answer) { if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.screen),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            item {
                Text(
                    "Questions are answered only from the ${ranked.size} alerts stored on this phone.",
                    style = Lifeline.type.caption, color = c.inkMuted, modifier = Modifier.padding(top = Space.m),
                )
            }
            items(chat, key = { it.id }) { ex ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Text(ex.question, style = Lifeline.type.body, color = c.ink,
                            modifier = Modifier.widthIn(max = 300.dp).background(c.surfaceRaised, Shapes.card).padding(Space.m))
                    }
                    val a = ex.answer
                    Column(
                        Modifier.widthIn(max = 340.dp).background(c.surface, Shapes.card).padding(Space.m),
                        verticalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        if (a == null) {
                            Text("Reading ${ex.alerts} stored alerts…", style = Lifeline.type.body, color = c.inkMuted)
                        } else {
                            Text(a.text, style = Lifeline.type.body, color = c.ink)
                            if (a.cites.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                a.cites.forEach { n ->
                                    Box(
                                        Modifier.heightIn(min = 36.dp).border(1.dp, c.link, Shapes.chip)
                                            .clickable(role = Role.Button) { open = n }.padding(horizontal = Space.m),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("#$n", style = Lifeline.type.label, color = c.link) }
                                }
                            }
                            Text(
                                if (a.fromModel) "Answered from ${ex.alerts} stored alerts · Written on device" else "Rules",
                                style = Lifeline.type.caption, color = c.inkMuted,
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            AskMesh.QUICK.forEach { q ->
                Box(
                    Modifier.heightIn(min = Touch.standard).background(c.surface, Shapes.chip).border(1.dp, c.outline, Shapes.chip)
                        .clickable(role = Role.Button) { vm.ask(q, ranked) }.padding(horizontal = Space.m),
                    contentAlignment = Alignment.Center,
                ) { Text(q, style = Lifeline.type.label, color = c.ink) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.screen).padding(bottom = Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it.take(300) }, singleLine = true,
                placeholder = { Text("Ask about the stored alerts", style = Lifeline.type.body) },
                textStyle = Lifeline.type.body, modifier = Modifier.weight(1f),
            )
            PrimaryButton("Ask", { vm.ask(input, ranked); input = "" }, enabled = input.isNotBlank())
        }
    }

    open?.let { n ->
        val r = ranked.firstOrNull { it.rank == n }
        if (r == null) open = null
        else AlertDetailSheet(r, now, onSend = { eta, note -> repo.sendAcks(r.alert.reports.map { it.id }, eta, note); open = null }, onDismiss = { open = null })
    }
}
