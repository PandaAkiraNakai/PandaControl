package io.github.pandaakira.apppanda.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.theme.PandaCyan
import io.github.pandaakira.apppanda.ui.theme.PandaMagenta
import io.github.pandaakira.apppanda.ui.theme.PandaOrange

private data class MediaEntry(
    val route: String,
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color,
)

@Composable
fun MediaTabScreen(onNavigate: (String) -> Unit) {
    val entries = listOf(
        MediaEntry("media", "Reproductor", "MPRIS · play/pause · seek · fullscreen",
            Icons.Outlined.Bolt, PandaMagenta),
        MediaEntry("audio", "Audio", "sinks pactl · cambiar default",
            Icons.Outlined.MusicNote, PandaOrange),
        MediaEntry("displays", "Pantallas", "niri outputs · DPMS",
            Icons.Outlined.Tv, PandaCyan),
        MediaEntry("apps", "Apps", "lanzar GUI vía systemd-run",
            Icons.Outlined.Apps, PandaCyan),
        MediaEntry("games", "Juegos", "biblioteca Steam · gamescope-auto",
            Icons.Outlined.SportsEsports, PandaOrange),
    )

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenHeader("MEDIA :: SESSION", "control de la sesión gráfica")
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries) { e ->
                MediaTile(e) { onNavigate(e.route) }
            }
        }
    }
}

@Composable
private fun MediaTile(entry: MediaEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, entry.color.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
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
