package com.lifeline.mesh.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lifeline.mesh.R

/*
 * Bundled fonts (res/font, SIL OFL, from github.com/google/fonts):
 * - archivo_variable.ttf: Archivo variable, axes wght 100-900, wdth 62-125. Condensed = width 80.
 * - atkinson_regular.ttf / atkinson_bold.ttf: Atkinson Hyperlegible, Latin only.
 * Hindi and Kannada glyphs are not in these fonts; Android falls back to system Noto per glyph.
 */

private const val CONDENSED_WIDTH = 80f

private fun archivo(weight: Int, width: Float) = Font(
    R.font.archivo_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

val ArchivoCondensed = FontFamily(archivo(700, CONDENSED_WIDTH), archivo(800, CONDENSED_WIDTH))
val Archivo = FontFamily(archivo(700, 100f))
val Atkinson = FontFamily(
    Font(R.font.atkinson_regular, FontWeight.Normal),
    Font(R.font.atkinson_bold, FontWeight.Bold),
)

/** Type scale from docs/DESIGN.md section 3. Body is 17 sp on purpose: readers are stressed. */
@Immutable
data class LifelineType(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
)

val DefaultType = LifelineType(
    display = TextStyle(fontFamily = ArchivoCondensed, fontWeight = FontWeight(800), fontSize = 44.sp, lineHeight = 48.sp),
    headline = TextStyle(fontFamily = ArchivoCondensed, fontWeight = FontWeight(700), fontSize = 28.sp, lineHeight = 32.sp),
    title = TextStyle(fontFamily = Archivo, fontWeight = FontWeight(700), fontSize = 20.sp, lineHeight = 26.sp),
    body = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 25.sp),
    bodyStrong = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 25.sp),
    label = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
    caption = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
)

val LocalLifelineType = staticCompositionLocalOf { DefaultType }
