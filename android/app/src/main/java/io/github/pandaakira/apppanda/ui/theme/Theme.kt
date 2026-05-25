package io.github.pandaakira.apppanda.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.data.models.ThemeColors
import io.github.pandaakira.apppanda.data.models.ThemeDef

/**
 * Paleta completa de la app. Cubre los roles de Material (background, surface,
 * texto, primary/secondary/tertiary, error) y además los acentos de marca que
 * las pantallas usan directo (yellow/magenta/cyan/green/red/orange).
 */
data class PandaPalette(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val yellow: Color,
    val magenta: Color,
    val cyan: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
)

/** Formas y bordes del tema: radio de esquinas (tarjetas y chips) y grosor de
 *  borde. `border = 0.dp` = sin borde. */
data class PandaShapes(
    val corner: Dp,
    val cornerSmall: Dp,
    val border: Dp,
)

/** Tema resuelto en tipos de Compose: paleta + fuente + iconos + formas +
 *  nombre de la imagen de fondo opcional (archivo en la carpeta del PC). */
data class PandaTheme(
    val palette: PandaPalette,
    val font: PandaFont,
    val iconStyle: IconStyle,
    val shapes: PandaShapes,
    val backgroundImage: String = "",
)

/** Paleta cyberpunk incluida — el tema por defecto y el fallback. */
val BuiltInPalette = PandaPalette(
    background = PandaBackground,
    surface = PandaSurface,
    surfaceHigh = PandaSurfaceHigh,
    onSurface = PandaOnSurface,
    onSurfaceMuted = PandaOnSurfaceMuted,
    yellow = PandaYellow,
    magenta = PandaMagenta,
    cyan = PandaCyan,
    green = PandaGreen,
    red = PandaRed,
    orange = PandaOrange,
)

/** Deriva el set de formas a partir del radio y grosor (en dp) del tema. */
fun pandaShapes(cornerDp: Int, borderDp: Int): PandaShapes = PandaShapes(
    corner = cornerDp.coerceIn(0, 48).dp,
    cornerSmall = (cornerDp - 4).coerceIn(0, 48).dp,
    border = borderDp.coerceIn(0, 8).dp,
)

val BuiltInShapes = pandaShapes(12, 1)

/** Tema incluido (Cyberpunk): mono+sans, iconos outlined, esquinas 12, borde 1. */
val BuiltInTheme = PandaTheme(
    palette = BuiltInPalette,
    font = PandaFont.Default,
    iconStyle = IconStyle.Outlined,
    shapes = BuiltInShapes,
)

/**
 * Acentos de marca disponibles para las pantallas vía `LocalPandaColors.current`.
 * Cambian enteros al aplicar un tema, repintando toda la app.
 */
val LocalPandaColors = staticCompositionLocalOf { BuiltInPalette }

/** Formas/bordes activos, para que tarjetas y contenedores cambien de forma. */
val LocalPandaShapes = staticCompositionLocalOf { BuiltInShapes }

private fun PandaPalette.toColorScheme(): ColorScheme = darkColorScheme(
    primary = yellow,
    onPrimary = background,
    secondary = magenta,
    onSecondary = Color.White,
    tertiary = cyan,
    onTertiary = background,
    background = background,
    onBackground = onSurface,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceHigh,
    onSurfaceVariant = onSurfaceMuted,
    error = red,
    onError = background,
)

private fun PandaShapes.toMaterialShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(cornerSmall),
    small = RoundedCornerShape(cornerSmall),
    medium = RoundedCornerShape(corner),
    large = RoundedCornerShape(corner),
    extraLarge = RoundedCornerShape(corner),
)

/** Parsea "#RRGGBB" o "#AARRGGBB" (case-insensitive). null si es inválido. */
fun parseHexColor(s: String): Color? {
    val h = s.trim().removePrefix("#")
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000L or v)
        8 -> Color(v)
        else -> null
    }
}

/** Mapea los colores de un tema del backend a [PandaPalette]. Cualquier color
 *  que no parsee cae al valor del built-in para ese rol. */
fun ThemeColors.toPalette(): PandaPalette = PandaPalette(
    background = parseHexColor(background) ?: BuiltInPalette.background,
    surface = parseHexColor(surface) ?: BuiltInPalette.surface,
    surfaceHigh = parseHexColor(surfaceHigh) ?: BuiltInPalette.surfaceHigh,
    onSurface = parseHexColor(onSurface) ?: BuiltInPalette.onSurface,
    onSurfaceMuted = parseHexColor(onSurfaceMuted) ?: BuiltInPalette.onSurfaceMuted,
    yellow = parseHexColor(yellow) ?: BuiltInPalette.yellow,
    magenta = parseHexColor(magenta) ?: BuiltInPalette.magenta,
    cyan = parseHexColor(cyan) ?: BuiltInPalette.cyan,
    green = parseHexColor(green) ?: BuiltInPalette.green,
    red = parseHexColor(red) ?: BuiltInPalette.red,
    orange = parseHexColor(orange) ?: BuiltInPalette.orange,
)

/** Convierte un tema del backend (strings) al tema resuelto de Compose. */
fun ThemeDef.toPandaTheme(): PandaTheme = PandaTheme(
    palette = colors.toPalette(),
    font = pandaFontFromName(font),
    iconStyle = iconStyleFromName(iconStyle),
    shapes = pandaShapes(corner, border),
    backgroundImage = backgroundImage,
)

@Composable
fun PandaControlTheme(
    theme: PandaTheme = BuiltInTheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPandaColors provides theme.palette,
        LocalPandaShapes provides theme.shapes,
        LocalIconStyle provides theme.iconStyle,
    ) {
        MaterialTheme(
            colorScheme = theme.palette.toColorScheme(),
            typography = pandaTypography(theme.font),
            shapes = theme.shapes.toMaterialShapes(),
            content = content,
        )
    }
}
