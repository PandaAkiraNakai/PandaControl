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

private data class WmCmd(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val color: Color,
)

private data class WmGroup(
    val label: String,
    val cmds: List<WmCmd>,
)

/**
 * Tarjeta de comandos de Hyprland (ventana, foco, workspaces, mover ventana y
 * paneles de Noctalia) con selector de monitor objetivo. Vive en la pantalla
 * "Mouse/Teclado", entre el touchpad y el teclado. Los botones siguen los
 * atajos de la config (binds.lua) y el backend los traduce a la API Lua de
 * Hyprland. Solo se dibujan los que el backend dice soportar (`commands` de
 * /screens), así los paneles de Noctalia desaparecen si no está instalada.
 * Es autocontenida: trae su propio ActionExecutor y muestra el resultado de la
 * última acción debajo.
 */
@Composable
fun HyprlandCommandsCard(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    val exec = rememberActionExecutor { api }

    // Monitor objetivo de los comandos. null = monitor enfocado.
    var outputs by remember { mutableStateOf<List<NiriOutput>>(emptyList()) }
    var supported by remember { mutableStateOf<Set<String>>(emptySet()) }
    var targetOutput by remember { mutableStateOf<String?>(null) }
    // Todos los monitores conectados (no solo los encendidos): si una
    // pantalla está en reposo igual debe poder elegirse como objetivo.
    io.github.pandaakira.apppanda.ui.components.PollingEffect(
        api = api, intervalMs = 30_000,
        onResult = {
            outputs = it.outputs
            supported = it.commands.toSet()
        },
        onError = {},
    ) { it.screens() }

    val c = LocalPandaColors.current
    val groups = listOf(
        WmGroup("ventana", listOf(
            WmCmd("fullscreen-window", "Fullscreen", PandaIcons.fullscreen,       c.magenta),
            WmCmd("maximize-window",   "Maximizar",  PandaIcons.aspectRatio,      c.orange),
            WmCmd("toggle-floating",   "Flotante",   PandaIcons.pictureInPicture, c.cyan),
            WmCmd("toggle-split",      "Dividir",    PandaIcons.verticalSplit,    c.cyan),
            WmCmd("pin-window",        "Fijar",      PandaIcons.pushPin,          c.yellow),
            WmCmd("close-window",      "Cerrar",     PandaIcons.close,            MaterialTheme.colorScheme.error),
        )),
        WmGroup("foco", listOf(
            WmCmd("focus-left",   "←",         PandaIcons.chevronLeft,       c.cyan),
            WmCmd("focus-up",     "↑",         PandaIcons.keyboardArrowUp,   c.cyan),
            WmCmd("focus-right",  "→",         PandaIcons.chevronRight,      c.cyan),
            WmCmd("focus-down",   "↓",         PandaIcons.keyboardArrowDown, c.cyan),
            WmCmd("cycle-window", "Siguiente", PandaIcons.swapHoriz,         c.cyan),
        )),
        WmGroup("workspaces del monitor", listOf(
            WmCmd("workspace-prev",  "WS ←",       PandaIcons.chevronLeft,  c.yellow),
            WmCmd("workspace-next",  "WS →",       PandaIcons.chevronRight, c.yellow),
            WmCmd("workspace-empty", "WS vacío",   PandaIcons.add,          c.yellow),
            WmCmd("toggle-special",  "Scratchpad", PandaIcons.inventory2,    c.green),
        )),
        WmGroup("mover ventana", listOf(
            WmCmd("move-workspace-prev", "A WS ←",       PandaIcons.chevronLeft,    c.orange),
            WmCmd("move-workspace-next", "A WS →",       PandaIcons.chevronRight,   c.orange),
            WmCmd("move-to-special",     "A scratchpad", PandaIcons.inventory2,      c.green),
            WmCmd("move-monitor-next",   "Otro monitor", PandaIcons.desktopWindows, c.magenta),
        )),
        WmGroup("noctalia", listOf(
            WmCmd("window-switcher", "Ventanas", PandaIcons.gridView, c.cyan),
            WmCmd("launcher",        "Launcher", PandaIcons.apps,     c.green),
            WmCmd("control-center",  "Panel",    PandaIcons.tune,     c.magenta),
        )),
    )
        // Con un backend viejo `commands` llega vacío: se muestran todos.
        .map { g -> g.copy(cmds = g.cmds.filter { supported.isEmpty() || it.id in supported }) }
        .filter { it.cmds.isNotEmpty() }

    PandaCard(title = "COMANDOS :: hyprland", accent = c.cyan) {
        Text(
            "atajos del WM · toca para disparar",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Selector de monitor objetivo: enfoca esa pantalla antes del comando.
        if (outputs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "monitor objetivo · fija el foco ahí",
                style = MaterialTheme.typography.labelSmall,
                color = c.cyan,
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
                        // Fija el monitor al instante: enfoca ese output, así
                        // las apps que lances después abren en esa pantalla.
                        targetOutput = o.name
                        exec.run("Foco → $name") {
                            it.wmCmd("focus-monitor", o.name)
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
                color = c.cyan,
            )
            Spacer(Modifier.height(6.dp))
            group.cmds.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { cmd ->
                        CmdButton(
                            cmd = cmd,
                            enabled = !exec.busy && api != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            exec.run(cmd.label) { it.wmCmd(cmd.id, targetOutput) }
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
    cmd: WmCmd,
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
