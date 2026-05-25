package io.github.pandaakira.apppanda.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Fuente del tema. `Default` conserva el look mixto del tema incluido
 *  (titulares monospace + cuerpo sans); el resto aplica una sola familia. */
enum class PandaFont { Default, Sans, Serif, Mono }

fun pandaFontFromName(name: String): PandaFont = when (name.trim().lowercase()) {
    "sans" -> PandaFont.Sans
    "serif" -> PandaFont.Serif
    "mono" -> PandaFont.Mono
    else -> PandaFont.Default
}

/**
 * Construye la tipografía. `accent` se usa en titulares y etiquetas tipo
 * terminal; `body` en cuerpo y títulos de tarjeta. En el tema incluido son
 * distintas (mono + sans); en los demás, la misma familia para todo.
 */
private fun buildTypography(accent: FontFamily, body: FontFamily) = Typography(
    displayLarge = TextStyle(
        fontFamily = accent,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = accent,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = accent,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = accent,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = body,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = body,
        fontSize = 14.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = accent,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = accent,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
    ),
)

fun pandaTypography(font: PandaFont): Typography = when (font) {
    PandaFont.Default -> buildTypography(FontFamily.Monospace, FontFamily.SansSerif)
    PandaFont.Sans -> buildTypography(FontFamily.SansSerif, FontFamily.SansSerif)
    PandaFont.Serif -> buildTypography(FontFamily.Serif, FontFamily.Serif)
    PandaFont.Mono -> buildTypography(FontFamily.Monospace, FontFamily.Monospace)
}

/** Tipografía del tema incluido (mono + sans). */
val PandaTypography = pandaTypography(PandaFont.Default)
