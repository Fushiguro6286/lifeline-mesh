package com.lifeline.mesh.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Shapes, spacing and touch targets from docs/DESIGN.md section 4. Elevation is flat everywhere. */
object Shapes {
    val chip = RoundedCornerShape(6.dp)
    val card = RoundedCornerShape(12.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val circle = CircleShape
}

object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screen = 16.dp
}

object Touch {
    /** Emergency actions. */
    val emergency = 56.dp
    /** Everything else. */
    val standard = 48.dp
}
