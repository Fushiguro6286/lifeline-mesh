package com.lifeline.mesh.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.LifelineApp
import com.lifeline.mesh.R
import com.lifeline.mesh.ai.scriptLanguage
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.Touch
import kotlinx.coroutines.launch

/**
 * A note in quotes with on-device translation into [target] (CLAUDE.md 9.6), labelled "Machine translated".
 * [auto] translates by itself when the note is in another script (the sender's reply card); otherwise a Translate
 * button appears when the model can translate. On failure the original stays, and says so.
 */
@Composable
fun TranslatableNote(text: String, target: String, auto: Boolean = false, modifier: Modifier = Modifier) {
    val c = Lifeline.colors
    val ai = (LocalContext.current.applicationContext as LifelineApp).ai
    val scope = rememberCoroutineScope()
    var translated by remember(text, target) { mutableStateOf<String?>(null) }
    var working by remember(text, target) { mutableStateOf(false) }
    var failed by remember(text, target) { mutableStateOf(false) }
    val possible = ai.canUse(target)

    suspend fun run() {
        working = true; failed = false
        translated = ai.translate(text, target)
        failed = translated == null
        working = false
    }
    if (auto && possible && scriptLanguage(text) != target) LaunchedEffect(text, target) { run() }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text("“$text”", style = Lifeline.type.body, color = c.ink)
        translated?.let {
            Text(it, style = Lifeline.type.body, color = c.ink)
            Text(stringResource(R.string.machine_translated), style = Lifeline.type.caption, color = c.inkMuted)
        }
        if (failed) Text(stringResource(R.string.translate_failed), style = Lifeline.type.caption, color = c.inkMuted)
        if (working) Text(stringResource(R.string.translating), style = Lifeline.type.caption, color = c.inkMuted)
        if (!auto && possible && translated == null && !working) {
            Row(
                Modifier.heightIn(min = Touch.standard).clickable(role = Role.Button) { scope.launch { run() } }.padding(end = Space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Icon(painterResource(R.drawable.ic_translate), null, tint = c.link, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.translate_action), style = Lifeline.type.label, color = c.link)
            }
        }
    }
}
