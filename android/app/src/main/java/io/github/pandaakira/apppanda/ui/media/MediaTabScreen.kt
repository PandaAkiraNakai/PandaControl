package io.github.pandaakira.apppanda.ui.media
import io.github.pandaakira.apppanda.ui.theme.LocalPandaShapes
import io.github.pandaakira.apppanda.ui.theme.PandaIcons
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.components.ScreenHeader

private data class MediaEntry(
    val route: String,
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color,
)

@Composable
fun MediaTabScreen(app: PandaApp, onNavigate: (String) -> Unit) {
    val tiles = listOf(
        MediaEntry("media", "Reproductor", "MPRIS · seek · fullscreen · salida de audio",
            PandaIcons.bolt, LocalPandaColors.current.magenta),
        MediaEntry("input", "Mouse/Teclado", "touchpad · clics · atajos · comandos",
            PandaIcons.mouse, LocalPandaColors.current.green),
        MediaEntry("displays", "Pantallas", "niri outputs · DPMS",
            PandaIcons.tv, LocalPandaColors.current.cyan),
        MediaEntry("apps", "Apps", "lanzar GUI vía systemd-run",
            PandaIcons.apps, LocalPandaColors.current.cyan),
        MediaEntry("games", "Juegos", "biblioteca Steam · gamescope-auto",
            PandaIcons.sportsEsports, LocalPandaColors.current.orange),
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ScreenHeader("CONTROL :: SESIÓN", "reproductor · input · audio · pantallas · apps · juegos")
        Spacer(Modifier.height(8.dp))

        // Grid 2 columnas de tiles (manual). 5 entradas → 2 filas + media fila.
        // Los comandos del WM (niri) se movieron a "Mouse/Teclado" (tile input).
        tiles.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { e ->
                    MediaTile(e, modifier = Modifier.weight(1f)) { onNavigate(e.route) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MediaTile(entry: MediaEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .height(140.dp)
            .clip(RoundedCornerShape(LocalPandaShapes.current.corner))
            .background(MaterialTheme.colorScheme.surface)
            .border(LocalPandaShapes.current.border, entry.color.copy(alpha = 0.5f), RoundedCornerShape(LocalPandaShapes.current.corner))
            .clickable { onClick() }
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(entry.icon, contentDescription = entry.label, tint = entry.color)
        Column {
            Text(
                entry.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
