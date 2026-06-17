package io.github.pandaakira.apppanda.ui.docker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.DockerContainer
import io.github.pandaakira.apppanda.data.models.DockerResponse
import io.github.pandaakira.apppanda.ui.components.ActionResultBanner
import io.github.pandaakira.apppanda.ui.components.ConfirmDialog
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.components.pandaDeco
import io.github.pandaakira.apppanda.ui.components.rememberActionExecutor
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun stateColor(state: String): Color = when (state) {
    "running" -> LocalPandaColors.current.green
    "paused" -> LocalPandaColors.current.yellow
    "restarting" -> LocalPandaColors.current.orange
    "exited", "dead" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun DockerScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var data by remember { mutableStateOf<DockerResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) } // (name, action)
    val scope = rememberCoroutineScope()
    val exec = rememberActionExecutor { api }

    LaunchedEffect(api, refresh) {
        val current = api ?: return@LaunchedEffect
        try {
            data = withContext(Dispatchers.IO) { current.dockerList() }
            error = null
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName
        }
    }

    // Auto-refresh cada 5 s mientras la pantalla esté visible.
    LaunchedEffect(api) {
        if (api == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(5_000)
            refresh++
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            ScreenHeader(
                "DOCKER",
                data?.let { "${it.total} contenedores · auto-refresh 5s" }
                    ?: "cargando…",
            )
        }

        error?.let { item { ErrorCard(it) } }
        data?.error?.let { item { ErrorCard(it) } }
        data?.takeIf { !it.enabled }?.let {
            item { EmptyState("Docker deshabilitado en el config del backend.") }
        }

        if (data?.enabled == true && data?.containers?.isEmpty() == true) {
            item { EmptyState("No hay contenedores.") }
        }

        items(data?.containers ?: emptyList(), key = { it.id + it.name }) { c ->
            ContainerCard(
                c = c,
                api = api,
                busy = exec.busy,
                onAction = { act -> pending = c.name to act },
            )
        }

        item { ActionResultBanner(exec) }
    }

    pending?.let { (name, act) ->
        ConfirmDialog(
            title = pandaDeco("${act.uppercase()} $name"),
            message = "¿Ejecutar `docker $act $name`?",
            confirmLabel = act,
            onConfirm = {
                exec.run("$act $name") { it.dockerAction(name, act) }
                scope.launch { kotlinx.coroutines.delay(800); refresh++ }
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun ContainerCard(
    c: DockerContainer,
    api: io.github.pandaakira.apppanda.data.PandaApi?,
    busy: Boolean,
    onAction: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<String>?>(null) }
    var logsError by remember { mutableStateOf<String?>(null) }
    val accent = stateColor(c.state)
    val running = c.state == "running"

    LaunchedEffect(expanded, api) {
        if (expanded && logs == null && api != null) {
            try {
                logs = withContext(Dispatchers.IO) { api.dockerLogs(c.name, 200).lines }
                logsError = null
            } catch (e: Exception) {
                logsError = e.message ?: e::class.simpleName
            }
        }
    }

    PandaCard(title = c.image, accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                c.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                c.state,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }

        Spacer(Modifier.height(2.dp))
        Text(
            c.status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (c.ports.isNotBlank()) {
            Text(
                c.ports,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            if (running) {
                OutlinedButton(
                    onClick = { onAction("restart") }, enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("restart") }
                OutlinedButton(
                    onClick = { onAction("stop") }, enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("stop") }
            } else {
                OutlinedButton(
                    onClick = { onAction("start") }, enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("start") }
            }
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.weight(1f),
            ) { Text(if (expanded) "ocultar" else "logs") }
        }

        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 8.dp)) {
                logsError?.let { ErrorCard(it) }
                val lines = logs
                when {
                    lines == null && logsError == null ->
                        Text("cargando logs…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    lines != null && lines.isEmpty() ->
                        Text("(sin logs)", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    lines != null -> Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                    ) {
                        Text(
                            lines.takeLast(200).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
