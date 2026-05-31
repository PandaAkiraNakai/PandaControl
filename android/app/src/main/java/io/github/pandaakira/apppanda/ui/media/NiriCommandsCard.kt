package io.github.pandaakira.apppanda.ui.media

import io.github.pandaakira.apppanda.ui.theme.LocalPandaShapes
import io.github.pandaakira.apppanda.ui.theme.PandaIcons
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.NiriOutput
import io.github.pandaakira.apppanda.ui.components.ActionResultBanner
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.rememberActionExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class NiriCmd(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val color: Color,
)

private data class NiriGroup(
    val label: String,
    val cmds: List<NiriCmd>,
)

/**
 * Tarjeta de comandos del WM niri (ventana, foco, vistas) con selector de
 * monitor objetivo. Antes vivía en el tab Control (MediaTabScreen); ahora se
 * embebe en la pantalla "Mouse/Teclado", entre el touchpad y el teclado, que
 * es donde se buscan al controlar el PC. Es autocontenida: trae su propio
 * ActionExecutor y muestra el resultado de la última acción debajo.
 */
@Composable
fun NiriCommandsCard(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    val exec = rememberActionExecutor { api }

    // Monitor objetivo para los comandos de niri. null = monitor enfocado.
    var outputs by remember { mutableStateOf<List<NiriOutput>>(emptyList()) }
    var targetOutput by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(api) {
        val current = api ?: return@LaunchedEffect
        try {
            // Todos los monitores conectados (no solo los encendidos): si una
            // pantalla está en reposo igual debe poder elegirse como objetivo.
            outputs = withContext(Dispatchers.IO) { current.screens() }.outputs
        } catch (_: Exception) {
            // Si no se pudieron leer las pantallas queda solo la opción "Foco".
        }
    }

    val groups = listOf(
        NiriGroup("ventana", listOf(
            NiriCmd("fullscreen-window", "Fullscreen", PandaIcons.fullscreen,   LocalPandaColors.current.magenta),
            NiriCmd("maximize-column",   "Maximizar",  PandaIcons.aspectRatio,  LocalPandaColors.current.orange),
            NiriCmd("close-window",      "Cerrar",     PandaIcons.close,        MaterialTheme.colorScheme.error),
        )),
        NiriGroup("foco · columnas y workspaces", listOf(
            NiriCmd("focus-column-left",    "Col. ←", PandaIcons.chevronLeft,       LocalPandaColors.current.cyan),
            NiriCmd("focus-column-right",   "Col. →", PandaIcons.chevronRight,      LocalPandaColors.current.cyan),
            NiriCmd("focus-workspace-up",   "WS ↑",   PandaIcons.keyboardArrowUp,   LocalPandaColors.current.yellow),
            NiriCmd("focus-workspace-down", "WS ↓",   PandaIcons.keyboardArrowDown, LocalPandaColors.current.yellow),
        )),
        NiriGroup("vistas", listOf(
            NiriCmd("toggle-overview", "Overview", PandaIcons.gridView, LocalPandaColors.current.cyan),
            NiriCmd("media-workspace", "Media WS", PandaIcons.tv,       LocalPandaColors.current.green),
        )),
    )

    PandaCard(title = "COMANDOS :: niri", accent = LocalPandaColors.current.cyan) {
        Text(
            "atajos del WM · toca para disparar",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Selector de monitor objetivo: enfoca esa pantalla antes del comando.
        if (outputs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "monitor objetivo · fija foco y cursor",
                style = MaterialTheme.typography.labelSmall,
                color = LocalPandaColors.current.cyan,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MonitorPill(
                    label = "Foco",
                    selected = targetOutput == null,
                    enabled = !exec.busy,
                ) { targetOutput = null }
                outputs.forEach { o ->
                    val name = o.label.ifBlank { o.name }
                    MonitorPill(
                        label = name,
                        selected = targetOutput == o.name,
                        enabled = !exec.busy,
                    ) {
                        // Fija el monitor al instante: enfoca ese output (y
                        // con warp-mouse-to-focus el cursor se va ahí), así
                        // las apps que lances después abren en esa pantalla.
                        targetOutput = o.name
                        exec.run("Foco → $name") {
                            it.niriCmd("focus-monitor", o.name)
                        }
                    }
                }
            }
        }

        // Comandos agrupados por intención: cada grupo con su etiqueta y su
        // propia rejilla de 3 columnas. Así "Cerrar" (destructivo) queda en
        // "ventana" y no pegado a la navegación.
        groups.forEach { group ->
            Spacer(Modifier.height(12.dp))
            Text(
                group.label,
                style = MaterialTheme.typography.labelSmall,
                color = LocalPandaColors.current.cyan,
            )
            Spacer(Modifier.height(6.dp))
            group.cmds.chunked(3).forEach { row ->
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
                            exec.run(c.label) { it.niriCmd(c.id, targetOutput) }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    ActionResultBanner(exec)
}

@Composable
private fun MonitorPill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val accent = LocalPandaColors.current.cyan
    val alpha = if (enabled) 1f else 0.4f
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        } else {
            accent.copy(alpha = 0.7f * alpha)
        },
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(LocalPandaShapes.current.cornerSmall))
            .background(
                if (selected) accent.copy(alpha = 0.18f * alpha) else Color.Transparent,
            )
            .border(
                1.dp,
                accent.copy(alpha = (if (selected) 0.8f else 0.35f) * alpha),
                RoundedCornerShape(LocalPandaShapes.current.cornerSmall),
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
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
            .clip(RoundedCornerShape(LocalPandaShapes.current.corner))
            .background(MaterialTheme.colorScheme.surface)
            .border(LocalPandaShapes.current.border, cmd.color.copy(alpha = 0.45f * alpha), RoundedCornerShape(LocalPandaShapes.current.corner))
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
