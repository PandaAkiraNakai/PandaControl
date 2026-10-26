package io.github.pandaakira.apppanda.ui.more

import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.RemoteScreen

// ─── Logs ────────────────────────────────────────────────────────────────────

@Composable
fun LogsScreen(app: PandaApp) {
    var priority by remember { mutableStateOf("err") }
    RemoteScreen(
        app = app,
        title = "LOGS :: journalctl",
        subtitle = "priority = $priority · n = 50",
        refreshKey = priority,
        fetch = { it.logs(priority = priority, n = 50) },
    ) { data ->
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("emerg", "alert", "crit", "err", "warning", "notice", "info")
                    .forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p) },
                        )
                    }
            }
        }
        item {
            PandaCard(title = data.priority.uppercase(), accent = LocalPandaColors.current.magenta) {
                Text(
                    data.output.ifBlank { "(sin entradas)" },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
