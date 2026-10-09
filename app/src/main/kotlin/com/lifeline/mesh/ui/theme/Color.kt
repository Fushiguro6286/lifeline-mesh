package com.lifeline.mesh.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Color tokens from docs/DESIGN.md section 2. Color carries meaning only:
 * signal (amber) = moving, link (teal) = healthy, sos (red) = urgent, safe (green) = acknowledged.
 *
 * Contrast-checked (WCAG) on 9 Oct 2026. Changes from DESIGN.md, each to reach 4.5:1 as text:
 * - sosInk (new): red text on Night surfaces. #E5483B is only 4.06:1 on surface; #F06A5E is 5.29:1.
 *   The SOS fill keeps #E5483B (onFill #06131A on it is 4.77:1).
 * - Daylight signal #9A6A00 -> #8F6200 (was 4.40:1 on bg, now 4.98:1).
 * - Daylight safe #2E7D32 -> #2B7A30 (was 4.45:1 on surfaceRaised, now 4.64:1).
 */
@Immutable
data class LifelineColors(
    val bg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val outline: Color,
    val ink: Color,
    val inkMuted: Color,
    val signal: Color,
    val link: Color,
    val sos: Color,
    val sosInk: Color,
    val safe: Color,
    val warn: Color,
    /** Text and icons on sos, link, signal, safe and warn fills. */
    val onFill: Color,
    val isNight: Boolean,
)

val NightColors = LifelineColors(
    bg = Color(0xFF0B1620),
    surface = Color(0xFF132330),
    surfaceRaised = Color(0xFF1A2E3D),
    outline = Color(0xFF27404F),
    ink = Color(0xFFE8EEF2),
    inkMuted = Color(0xFF8FA3B0),
    signal = Color(0xFFF2A900),
    link = Color(0xFF2BB3A8),
    sos = Color(0xFFE5483B),
    sosInk = Color(0xFFF06A5E),
    safe = Color(0xFF5BC27A),
    warn = Color(0xFFF0C14B),
    onFill = Color(0xFF06131A),
    isNight = true,
)

val DaylightColors = LifelineColors(
    bg = Color(0xFFF4F7F8),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEAF0F2),
    outline = Color(0xFFC9D6DA),
    ink = Color(0xFF0E1E28),
    inkMuted = Color(0xFF4E626D),
    signal = Color(0xFF8F6200),
    link = Color(0xFF0B6E75),
    sos = Color(0xFFC8251D),
    sosInk = Color(0xFFC8251D),
    safe = Color(0xFF2B7A30),
    warn = Color(0xFF8A5F00),
    onFill = Color(0xFFFFFFFF),
    isNight = false,
)

/** Severity 3 Urgent = sos, 2 High = signal, 1 Moderate = link, 0 Info = inkMuted. */
fun LifelineColors.severity(level: Int): Color = when (level) {
    3 -> sos
    2 -> signal
    1 -> link
    else -> inkMuted
}

val LocalLifelineColors = staticCompositionLocalOf { NightColors }
