package io.github.pandaakira.apppanda.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.data.models.Scene
import io.github.pandaakira.apppanda.ui.components.ActionExecutor
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors

// ─── Escenas (Pantallas y Rápido) ────────────────────────────────────────────

/** Presets de monitores + audio de un toque. [onApplied] corre tras mandar uno. */
@Composable
internal fun ScenesCard(scenes: List<Scene>, exec: ActionExecutor, onApplied: () -> Unit) {
    PandaCard(title = "ESCENAS", accent = LocalPandaColors.current.magenta) {
        Text("aplica un preset de monitores",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            scenes.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { sc ->
                        OutlinedButton(
                            onClick = {
                                exec.run("Escena ${sc.label}") { it.applyScene(sc.name) }
                                onApplied()
                            },
                            enabled = !exec.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(sc.label, maxLines = 1) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
