package io.github.pandaakira.apppanda.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PandaColors = darkColorScheme(
    primary = PandaYellow,
    onPrimary = PandaBackground,
    secondary = PandaMagenta,
    onSecondary = Color.White,
    tertiary = PandaCyan,
    onTertiary = PandaBackground,
    background = PandaBackground,
    onBackground = PandaOnSurface,
    surface = PandaSurface,
    onSurface = PandaOnSurface,
    surfaceVariant = PandaSurfaceHigh,
    onSurfaceVariant = PandaOnSurfaceMuted,
    error = PandaRed,
    onError = PandaBackground,
)

@Composable
fun PandaControlTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = PandaColors,
        typography = PandaTypography,
        content = content,
    )
}
