package com.lifeline.mesh.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Motion
import com.lifeline.mesh.ui.theme.PreviewBox
import com.lifeline.mesh.ui.theme.ThemePreviews

/**
 * The app's one motif: 3 rings pulsing outward from the center of this box.
 * Each ring scales 1.0 -> 2.2 of the box radius and fades 0.5 -> 0 over 1.2 s, staggered 400 ms.
 * Rings draw outside the box bounds on purpose; place this behind the thing that "emits".
 * With reduced motion, a single static ring is shown while active.
 */
@Composable
fun SignalRings(
    color: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
) {
    if (!active) return
    if (Lifeline.reducedMotion) {
        Canvas(modifier) {
            drawCircle(color.copy(alpha = Motion.RING_ALPHA_FROM), radius = size.minDimension / 2 * 1.3f,
                style = Stroke(strokeWidth.toPx()))
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "rings")
    // One shared clock; ring i is offset by i * stagger / period of a cycle.
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(Motion.RING_PERIOD, easing = LinearEasing), RepeatMode.Restart),
        label = "ringClock",
    )
    Canvas(modifier) {
        val base = size.minDimension / 2
        val stroke = Stroke(strokeWidth.toPx())
        for (i in 0 until Motion.RING_COUNT) {
            val phase = (t + i * Motion.RING_STAGGER.toFloat() / Motion.RING_PERIOD) % 1f
            val scale = 1f + (Motion.RING_SCALE_TO - 1f) * phase
            val alpha = Motion.RING_ALPHA_FROM * (1f - phase)
            drawCircle(color.copy(alpha = alpha), radius = base * scale, style = stroke)
        }
    }
}

@ThemePreviews
@Composable
private fun SignalRingsPreview() = PreviewBox {
    Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
        SignalRings(Lifeline.colors.signal, active = true, modifier = Modifier.size(56.dp))
        Box(Modifier.size(56.dp).background(Lifeline.colors.link, CircleShape))
    }
}
