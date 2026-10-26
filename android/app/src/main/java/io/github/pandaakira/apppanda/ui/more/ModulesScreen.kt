package io.github.pandaakira.apppanda.ui.more

import io.github.pandaakira.apppanda.ui.theme.LocalPandaShapes
import io.github.pandaakira.apppanda.ui.theme.PandaIcons
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.ui.components.ScreenHeader

// ─── MoreScreen: grid de accesos a las pantallas extra ───────────────────────

private data class ModuleEntry(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val color: Color,
)

@Composable
fun ModulesScreen(onNavigate: (String) -> Unit) {
    val modules = listOf(
        ModuleEntry("services", "Servicios", PandaIcons.tune, LocalPandaColors.current.cyan),
        ModuleEntry("docker", "Docker", PandaIcons.inventory2, LocalPandaColors.current.cyan),
        ModuleEntry("memorias", "Memorias", PandaIcons.psychology, LocalPandaColors.current.magenta),
        ModuleEntry("pedidos", "Pedidos", PandaIcons.checklist, LocalPandaColors.current.yellow),
        ModuleEntry("updates", "Actualizar", PandaIcons.systemUpdate, LocalPandaColors.current.green),
        ModuleEntry("power", "Energía", PandaIcons.powerSettingsNew,
            MaterialTheme.colorScheme.error),
        ModuleEntry("files", "Archivos", PandaIcons.folder, LocalPandaColors.current.green),
        ModuleEntry("network", "Red LAN", PandaIcons.wifi, LocalPandaColors.current.green),
        ModuleEntry("vps", "VPS", PandaIcons.cloud, LocalPandaColors.current.yellow),
        ModuleEntry("terminal", "Terminal", PandaIcons.dns, LocalPandaColors.current.cyan),
        ModuleEntry("temas", "Temas", PandaIcons.palette, LocalPandaColors.current.magenta),
    )

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                ScreenHeader("SISTEMA", "administración de la máquina")
            }
            IconButton(onClick = { onNavigate("settings") }) {
                Icon(
                    PandaIcons.settings,
                    contentDescription = "Ajustes",
                    tint = LocalPandaColors.current.cyan,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            gridItems(modules) { entry ->
                ModuleCard(entry) { onNavigate(entry.route) }
            }
        }
    }
}

@Composable
private fun ModuleCard(entry: ModuleEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(LocalPandaShapes.current.corner))
            .background(MaterialTheme.colorScheme.surface)
            .border(LocalPandaShapes.current.border, entry.color.copy(alpha = 0.5f), RoundedCornerShape(LocalPandaShapes.current.corner))
            .clickable { onClick() }
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(
            entry.icon,
            contentDescription = entry.label,
            tint = entry.color,
        )
        Text(
            entry.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
