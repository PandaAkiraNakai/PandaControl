package io.github.pandaakira.apppanda.ui.media

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.components.ActionResultBanner
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.components.rememberActionExecutor
import io.github.pandaakira.apppanda.ui.theme.PandaCyan
import io.github.pandaakira.apppanda.ui.theme.PandaGreen
import io.github.pandaakira.apppanda.ui.theme.PandaMagenta
import io.github.pandaakira.apppanda.ui.theme.PandaOrange
import io.github.pandaakira.apppanda.ui.theme.PandaYellow

private data class MediaEntry(
    val route: String,
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color,
)

private data class NiriCmd(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val color: Color,
)

@Composable
fun MediaTabScreen(app: PandaApp, onNavigate: (String) -> Unit) {
    val api by app.repository.api.collectAsState()
    val exec = rememberActionExecutor { api }

    val tiles = listOf(
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

    val cmds = listOf(
        NiriCmd("fullscreen-window",   "Fullscreen",  Icons.Outlined.Fullscreen,         PandaMagenta),
        NiriCmd("close-window",        "Cerrar",      Icons.Outlined.Close,              MaterialTheme.colorScheme.error),
        NiriCmd("maximize-column",     "Maximizar",   Icons.Outlined.AspectRatio,        PandaOrange),
        NiriCmd("focus-column-left",   "Col. ←",      Icons.Outlined.ChevronLeft,        PandaCyan),
        NiriCmd("focus-column-right",  "Col. →",      Icons.Outlined.ChevronRight,       PandaCyan),
        NiriCmd("focus-workspace-up",   "WS ↑",       Icons.Outlined.KeyboardArrowUp,    PandaYellow),
        NiriCmd("focus-workspace-down", "WS ↓",       Icons.Outlined.KeyboardArrowDown,  PandaYellow),
        NiriCmd("toggle-overview",     "Overview",    Icons.Outlined.GridView,           PandaCyan),
        NiriCmd("media-workspace",     "Media WS",    Icons.Outlined.Tv,                 PandaGreen),
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ScreenHeader("MEDIA :: SESSION", "control de la sesión gráfica")
        Spacer(Modifier.height(8.dp))

        // Grid 2 columnas de tiles (manual, para poder scrollear con la
        // sección Comandos abajo). 5 entradas → 2 filas + media fila.
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

        Spacer(Modifier.height(4.dp))

        PandaCard(title = "COMANDOS :: niri", accent = PandaCyan) {
            Text(
                "atajos del WM · tocá para disparar",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // Grid 3 columnas, manual.
            cmds.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { c ->
                        CmdButton(
                            cmd = c,
                            enabled = !exec.busy && api != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            exec.run(c.label) { it.niriCmd(c.id) }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        ActionResultBanner(exec)
    }
}

@Composable
private fun MediaTile(entry: MediaEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
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

@Composable
private fun CmdButton(
    cmd: NiriCmd,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Column(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, cmd.color.copy(alpha = 0.45f * alpha), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 8.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            cmd.icon,
            contentDescription = cmd.label,
            tint = cmd.color.copy(alpha = alpha),
        )
        Text(
            cmd.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
