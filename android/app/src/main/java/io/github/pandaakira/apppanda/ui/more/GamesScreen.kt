package io.github.pandaakira.apppanda.ui.more

import io.github.pandaakira.apppanda.ui.theme.LocalPandaShapes
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.pandaDeco
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import kotlinx.coroutines.launch

// ─── Juegos Steam ────────────────────────────────────────────────────────────

@Composable
fun GamesScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var games by remember { mutableStateOf<List<io.github.pandaakira.apppanda.data.models.Game>?>(null) }
    var useGamescope by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf<io.github.pandaakira.apppanda.data.models.RunningGame?>(null) }
    var runningRefresh by remember { mutableStateOf(0) }
    var confirmClose by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val exec = io.github.pandaakira.apppanda.ui.components.rememberActionExecutor { api }

    io.github.pandaakira.apppanda.ui.components.PollingEffect(
        api = api, intervalMs = 30_000,
        onResult = { games = it.games; useGamescope = it.useGamescope },
        onError = { error = it },
    ) { it.games() }

    io.github.pandaakira.apppanda.ui.components.PollingEffect(
        api = api, key = runningRefresh, intervalMs = 5_000,
        onResult = { running = it }, onError = {},
    ) { runCatching { it.runningGame().running }.getOrNull() }

    if (confirmClose) {
        io.github.pandaakira.apppanda.ui.components.ConfirmDialog(
            title = "¿Cerrar el juego?",
            message = "Se enviará SIGTERM a «${running?.name ?: "el juego"}». Podrías perder progreso sin guardar.",
            confirmLabel = "Cerrar",
            onConfirm = {
                confirmClose = false
                exec.run("Cerrar ${running?.name?.take(18) ?: "juego"}") { it.closeGame() }
                scope.launch { kotlinx.coroutines.delay(1500); runningRefresh++ }
            },
            onDismiss = { confirmClose = false },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        ScreenHeader(
            "STEAM :: library",
            "Gamescope ${if (useGamescope) "ON" else "OFF"} · ${games?.size ?: "—"} juegos",
        )
        Spacer(Modifier.height(8.dp))
        running?.let { r ->
            PandaCard(title = "EN CURSO", accent = LocalPandaColors.current.green) {
                Text(r.name, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Button(
                    onClick = { confirmClose = true },
                    enabled = !exec.busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Cerrar juego") }
            }
            Spacer(Modifier.height(8.dp))
        }
        when {
            api == null -> EmptyState("Configura el backend en Ajustes.")
            error != null -> ErrorCard(error!!)
            games == null -> EmptyState("Cargando…")
            games!!.isEmpty() -> EmptyState("Sin juegos detectados en la biblioteca Steam.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                gridItems(games!!) { g ->
                    GameTile(g, enabled = !exec.busy) {
                        exec.run("Steam → ${g.name.take(20)}") { it.launchGame(g.appid) }
                    }
                }
            }
        }
        io.github.pandaakira.apppanda.ui.components.ActionResultBanner(exec)
    }
}

@Composable
private fun GameTile(
    g: io.github.pandaakira.apppanda.data.models.Game,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(LocalPandaShapes.current.corner))
            .background(MaterialTheme.colorScheme.surface)
            .border(LocalPandaShapes.current.border, LocalPandaColors.current.magenta.copy(alpha = 0.5f), RoundedCornerShape(LocalPandaShapes.current.corner))
            .clickable(enabled = enabled) { onClick() }
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            pandaDeco("${g.appid}"),
            style = MaterialTheme.typography.labelSmall,
            color = LocalPandaColors.current.magenta,
        )
        Text(
            g.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
        )
    }
}
