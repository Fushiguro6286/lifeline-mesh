package com.lifeline.mesh.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes as M3Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.lifeline.mesh.data.ThemeMode

/** Access tokens as `Lifeline.colors.link`, `Lifeline.type.body`. */
object Lifeline {
    val colors: LifelineColors
        @Composable @ReadOnlyComposable get() = LocalLifelineColors.current
    val type: LifelineType
        @Composable @ReadOnlyComposable get() = LocalLifelineType.current
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

@Composable
fun LifelineTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val night = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.NIGHT -> true
        ThemeMode.DAYLIGHT -> false
    }
    val colors = if (night) NightColors else DaylightColors
    val context = LocalContext.current
    val reduced = remember(context) { isReducedMotion(context) }
    CompositionLocalProvider(
        LocalLifelineColors provides colors,
        LocalLifelineType provides DefaultType,
        LocalReducedMotion provides reduced,
    ) {
        MaterialTheme(
            colorScheme = materialScheme(colors),
            typography = materialTypography(DefaultType),
            shapes = M3Shapes(
                extraSmall = Shapes.chip,
                small = Shapes.chip,
                medium = Shapes.card,
                large = Shapes.card,
                extraLarge = RoundedCornerShape(20.dp),
            ),
            content = content,
        )
    }
}

/** Material components (text fields, dialogs, switches) read these; we map our tokens onto them. */
private fun materialScheme(c: LifelineColors) = if (c.isNight) {
    darkColorScheme(
        primary = c.link, onPrimary = c.onFill, primaryContainer = c.surfaceRaised, onPrimaryContainer = c.ink,
        secondary = c.signal, onSecondary = c.onFill, secondaryContainer = c.surfaceRaised, onSecondaryContainer = c.ink,
        tertiary = c.safe, onTertiary = c.onFill,
        error = c.sos, onError = c.onFill, errorContainer = c.surfaceRaised, onErrorContainer = c.sosInk,
        background = c.bg, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
        surfaceVariant = c.surfaceRaised, onSurfaceVariant = c.inkMuted,
        surfaceContainerLowest = c.bg, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceRaised, surfaceContainerHighest = c.surfaceRaised,
        outline = c.outline, outlineVariant = c.outline, scrim = c.bg,
    )
} else {
    lightColorScheme(
        primary = c.link, onPrimary = c.onFill, primaryContainer = c.surfaceRaised, onPrimaryContainer = c.ink,
        secondary = c.signal, onSecondary = c.onFill, secondaryContainer = c.surfaceRaised, onSecondaryContainer = c.ink,
        tertiary = c.safe, onTertiary = c.onFill,
        error = c.sos, onError = c.onFill, errorContainer = c.surfaceRaised, onErrorContainer = c.sosInk,
        background = c.bg, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
        surfaceVariant = c.surfaceRaised, onSurfaceVariant = c.inkMuted,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceRaised, surfaceContainerHighest = c.surfaceRaised,
        outline = c.outline, outlineVariant = c.outline, scrim = c.ink,
    )
}

private fun materialTypography(t: LifelineType) = Typography(
    displayLarge = t.display, displayMedium = t.display, displaySmall = t.display,
    headlineLarge = t.headline, headlineMedium = t.headline, headlineSmall = t.headline,
    titleLarge = t.title, titleMedium = t.bodyStrong, titleSmall = t.label,
    bodyLarge = t.body, bodyMedium = t.body, bodySmall = t.caption,
    labelLarge = t.label, labelMedium = t.label, labelSmall = t.caption,
)

/** Previews every component in both themes. Night follows uiMode, which LifelineTheme reads via SYSTEM. */
@Preview(name = "Night", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0B1620)
@Preview(name = "Daylight", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF4F7F8)
annotation class ThemePreviews

@Composable
fun PreviewBox(content: @Composable () -> Unit) {
    LifelineTheme {
        Box(Modifier.background(Lifeline.colors.bg).padding(Space.l)) { content() }
    }
}
