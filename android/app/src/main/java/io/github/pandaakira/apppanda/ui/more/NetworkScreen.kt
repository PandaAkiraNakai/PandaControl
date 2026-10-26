package io.github.pandaakira.apppanda.ui.more

import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.RemoteScreen

// ─── Red ─────────────────────────────────────────────────────────────────────

@Composable
fun NetworkScreen(app: PandaApp) = RemoteScreen(
    app = app,
    title = "NET :: LAN",
    fetch = { it.netNeighbors() },
) { data ->
    item {
        PandaCard(title = "GATEWAY", accent = LocalPandaColors.current.yellow) {
            Text(
                data.gateway.ifBlank { "(no detectado)" },
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    item {
        PandaCard(title = "NEIGHBORS :: ${data.neighbors.size}", accent = LocalPandaColors.current.cyan) {
            data.neighbors.forEach { n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    val mono = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace)
                    Text(n.ip, style = mono, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(120.dp))
                    Text(n.dev, style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp))
                    Text(
                        n.state,
                        style = mono,
                        color = when (n.state) {
                            "REACHABLE" -> LocalPandaColors.current.green
                            "STALE" -> LocalPandaColors.current.yellow
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    n.mac,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
