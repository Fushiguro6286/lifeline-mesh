package com.lifeline.mesh.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.R
import com.lifeline.mesh.safety.FallAlarm
import com.lifeline.mesh.ui.components.PrimaryButton
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import kotlinx.coroutines.delay

/**
 * Fall alarm countdown, over everything (also the sign-in screen). One huge "I'm OK" target, so a shaken person can
 * stop it with a thumb; "Send SOS now" for someone who is hurt. The service does the counting; this only shows it.
 */
@Composable
fun FallAlarmScreen(alarm: FallAlarm, onOk: () -> Unit, onSendNow: () -> Unit) {
    val c = Lifeline.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(alarm) { while (true) { now = System.currentTimeMillis(); delay(200) } }
    val left = alarm.secondsLeft(now)

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().padding(Space.screen)
            // Swallow taps so nothing underneath reacts while the alarm is up.
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Spacer(Modifier.height(Space.xl))
        if (alarm.test) Text(stringResource(R.string.fall_test), style = Lifeline.type.label, color = c.warn)
        Text(stringResource(R.string.fall_title), style = Lifeline.type.display, color = c.ink, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.fall_detected), style = Lifeline.type.body, color = c.inkMuted, textAlign = TextAlign.Center)
        Box(Modifier.size(140.dp).background(c.sos, Shapes.circle), contentAlignment = Alignment.Center) {
            Text("$left", style = Lifeline.type.display, color = c.onFill, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Text(stringResource(R.string.fall_countdown, left), style = Lifeline.type.bodyStrong, color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        // The whole lower half is "I'm OK".
        Box(
            Modifier.fillMaxWidth().height(160.dp).background(c.safe, Shapes.card).clickable(role = Role.Button, onClick = onOk),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.fall_ok), style = Lifeline.type.display, color = c.onFill) }
        PrimaryButton(stringResource(R.string.fall_send_now), onSendNow, Modifier.fillMaxWidth(), color = c.sos)
    }
}
