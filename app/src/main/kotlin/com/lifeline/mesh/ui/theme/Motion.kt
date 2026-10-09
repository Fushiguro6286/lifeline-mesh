package com.lifeline.mesh.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.staticCompositionLocalOf

/** Motion tokens from docs/DESIGN.md section 5. */
object Motion {
    const val FAST = 120
    const val BASE = 220
    const val SLOW = 400
    const val PACKET_HOP = 700

    /** Things arriving. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Everything else. */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val RING_COUNT = 3
    const val RING_PERIOD = 1_200
    const val RING_STAGGER = 400
    const val RING_SCALE_TO = 2.2f
    const val RING_ALPHA_FROM = 0.5f
}

/** True when the user turned on "Remove animations" (animator duration scale 0). */
fun isReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Read in components: replace movement with instant state changes and a brief color flash. */
val LocalReducedMotion = staticCompositionLocalOf { false }
