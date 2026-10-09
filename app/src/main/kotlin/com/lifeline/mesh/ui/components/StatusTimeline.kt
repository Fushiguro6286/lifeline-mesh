package com.lifeline.mesh.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.ui.theme.Lifeline
import com.lifeline.mesh.ui.theme.Motion
import com.lifeline.mesh.ui.theme.PreviewBox
import com.lifeline.mesh.ui.theme.Shapes
import com.lifeline.mesh.ui.theme.Space
import com.lifeline.mesh.ui.theme.ThemePreviews

enum class StepState { Done, Current, Pending }

/** [tone] overrides the dot color, e.g. `safe` for "Help is coming". */
data class TimelineStep(val title: String, val state: StepState, val detail: String? = null, val tone: Color? = null)

/** Vertical steps with dots: Created -> Waiting / Passed to N phones -> Help is coming. */
@Composable
fun StatusTimeline(steps: List<TimelineStep>, modifier: Modifier = Modifier) {
    Column(modifier) {
        steps.forEachIndexed { i, step -> TimelineRow(step, isLast = i == steps.lastIndex) }
    }
}

@Composable
private fun TimelineRow(step: TimelineStep, isLast: Boolean) {
    val c = Lifeline.colors
    val tone = step.tone ?: c.link
    val reduced = Lifeline.reducedMotion
    // The current step pulses once when it becomes current (a color flash would be the reduced-motion form;
    // the dot is already filled, so with reduced motion we simply show it).
    val pulse = remember(step.title, step.state) { Animatable(1f) }
    LaunchedEffect(step.title, step.state) {
        if (step.state == StepState.Current && !reduced) {
            pulse.animateTo(1.6f, tween(Motion.BASE, easing = Motion.EmphasizedDecelerate))
            pulse.animateTo(1f, tween(Motion.SLOW, easing = Motion.Standard))
        }
    }
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 6.dp).size(14.dp), contentAlignment = Alignment.Center) {
                when (step.state) {
                    StepState.Done -> Box(Modifier.size(12.dp).background(tone, Shapes.circle))
                    StepState.Current -> Box(Modifier.size(14.dp).scale(pulse.value).background(tone, Shapes.circle))
                    StepState.Pending -> Box(Modifier.size(12.dp).border(2.dp, c.outline, Shapes.circle))
                }
            }
            if (!isLast) {
                Box(Modifier.width(2.dp).weight(1f).padding(vertical = 2.dp)
                    .background(if (step.state == StepState.Done) tone else c.outline))
            }
        }
        Column(Modifier.padding(bottom = if (isLast) 0.dp else Space.l)) {
            Text(step.title, style = Lifeline.type.bodyStrong,
                color = if (step.state == StepState.Pending) c.inkMuted else c.ink)
            if (step.detail != null) {
                Text(step.detail, style = Lifeline.type.caption, color = c.inkMuted)
            }
        }
    }
}

@ThemePreviews
@Composable
private fun StatusTimelinePreview() = PreviewBox {
    StatusTimeline(
        listOf(
            TimelineStep("Created", StepState.Done),
            TimelineStep("Waiting for a nearby phone", StepState.Current, "Stay where you are; it will send automatically."),
            TimelineStep("Help is coming", StepState.Pending),
        )
    )
}
