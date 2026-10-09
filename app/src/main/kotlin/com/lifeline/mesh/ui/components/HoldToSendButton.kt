package com.lifeline.mesh.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeline.mesh.R
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Motion
import com.lifeline.mesh.ui.theme.PreviewBox
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.ThemePreviews
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

private enum class HoldPhase { Idle, Holding, Cancelled, Sent }

/**
 * 200 dp SOS circle. Hold for [holdMillis]: a progress ring fills, signal rings pulse, a haptic tick
 * each second, the label counts 3, 2, 1. Releasing early cancels. Completing gives a strong buzz and
 * calls [onSend]. TalkBack users get a "Send SOS" action that opens a confirmation dialog instead.
 */
@Composable
fun HoldToSendButton(
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = 3_000,
) {
    val c = Lifeline.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnSend by rememberUpdatedState(onSend)
    var phase by remember { mutableStateOf(HoldPhase.Idle) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val reduced = Lifeline.reducedMotion

    fun complete() {
        phase = HoldPhase.Sent
        Haptics.strong(context)
        currentOnSend()
    }

    // Tick once per second while holding (at 3 -> 2 -> 1).
    LaunchedEffect(phase) {
        when (phase) {
            HoldPhase.Holding -> {
                var last = -1
                snapshotFlow { secondsLeft(progress.value, holdMillis) }.collect { s ->
                    if (s != last) { last = s; Haptics.tick(context) }
                }
            }
            HoldPhase.Cancelled -> { delay(1_500); phase = HoldPhase.Idle }
            HoldPhase.Sent -> { delay(2_500); progress.snapTo(0f); phase = HoldPhase.Idle }
            HoldPhase.Idle -> Unit
        }
    }

    val label = when (phase) {
        HoldPhase.Idle -> stringResource(R.string.sos_hold)
        HoldPhase.Holding -> secondsLeft(progress.value, holdMillis).toString()
        HoldPhase.Cancelled -> stringResource(R.string.sos_cancelled)
        HoldPhase.Sent -> stringResource(R.string.sos_sent)
    }
    val a11yLabel = stringResource(R.string.sos_hold)
    val a11yAction = stringResource(R.string.sos_a11y_action)
    val pressedScale by animateFloatAsState(
        targetValue = if (phase == HoldPhase.Holding && !reduced) 0.97f else 1f,
        animationSpec = tween(Motion.FAST),
        label = "press",
    )

    Box(modifier.size(200.dp), contentAlignment = Alignment.Center) {
        SignalRings(c.sos, active = phase == HoldPhase.Holding, modifier = Modifier.size(200.dp), strokeWidth = 3.dp)
        Box(
            Modifier
                .size(200.dp)
                .scale(pressedScale)
                .clip(Shapes.circle)
                .background(c.sos)
                .semantics {
                    contentDescription = a11yLabel
                    role = Role.Button
                    onClick(label = a11yAction) { confirming = true; true }
                }
                .pointerInput(holdMillis) {
                    detectTapGestures(onPress = {
                        if (phase == HoldPhase.Sent) return@detectTapGestures
                        phase = HoldPhase.Holding
                        val hold = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                            complete()
                        }
                        tryAwaitRelease()
                        if (phase == HoldPhase.Holding) {
                            hold.cancel()
                            phase = HoldPhase.Cancelled
                            scope.launch { progress.snapTo(0f) }
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                if (phase == HoldPhase.Holding || phase == HoldPhase.Sent) {
                    val stroke = Stroke(8.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(c.onFill.copy(alpha = 0.25f), 0f, 360f, false, style = stroke)
                    drawArc(c.onFill, -90f, 360f * progress.value, false, style = stroke)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val big = phase == HoldPhase.Holding
                BasicText(
                    text = label,
                    style = (if (big) Lifeline.type.display.copy(fontSize = 64.sp, lineHeight = 64.sp) else Lifeline.type.headline)
                        .copy(color = c.onFill, textAlign = TextAlign.Center),
                    autoSize = if (big) null else TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 28.sp),
                    maxLines = 3,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
                if (big) {
                    Text(stringResource(R.string.sos_keep_holding), style = Lifeline.type.label, color = c.onFill)
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = c.surface,
            title = { Text(stringResource(R.string.sos_confirm_title), style = Lifeline.type.title, color = c.ink) },
            text = { Text(stringResource(R.string.sos_confirm_body), style = Lifeline.type.body, color = c.ink) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch { progress.snapTo(1f) }
                    complete()
                }) { Text(stringResource(R.string.sos_confirm_send), style = Lifeline.type.label, color = c.sosInk) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.cancel), style = Lifeline.type.label, color = c.ink)
                }
            },
        )
    }
}

private fun secondsLeft(progress: Float, holdMillis: Int): Int =
    ceil((1f - progress) * holdMillis / 1000f).toInt().coerceAtLeast(1)

@ThemePreviews
@Composable
private fun HoldToSendButtonPreview() = PreviewBox {
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) { HoldToSendButton(onSend = {}) }
}
