package io.github.pandaakira.apppanda.ui.more

import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.pandaDeco
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── Servicios ───────────────────────────────────────────────────────────────

@Composable
fun ServicesListScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var data by remember {
        mutableStateOf<io.github.pandaakira.apppanda.data.models.ServicesResponse?>(null)
    }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var pending by remember {
        mutableStateOf<Pair<String, String>?>(null)  // (unit, action)
    }
    val scope = rememberCoroutineScope()
    val exec = io.github.pandaakira.apppanda.ui.components.rememberActionExecutor { api }

    LaunchedEffect(api, refresh) {
        val current = api ?: return@LaunchedEffect
        try {
            data = withContext(Dispatchers.IO) { current.services() }
            error = null
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName
        }
    }

    // Auto-refresh cada 5 s mientras la pantalla esté visible.
    io.github.pandaakira.apppanda.ui.components.TickerEffect(api, 5_000) {
        if (api != null) refresh++
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ScreenHeader("SERVICES", "auto-refresh 5s · units inexistentes filtradas") }
        error?.let { item { ErrorCard(it) } }
        data?.let { d ->
            if (d.failed.isNotEmpty()) {
                item {
                    PandaCard(title = "FAILED :: ${d.failed.size}",
                        accent = MaterialTheme.colorScheme.error) {
                        d.failed.forEach { unit ->
                            Text(unit,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
            item {
                PandaCard(title = "WATCH :: ${d.watch.size}", accent = LocalPandaColors.current.cyan) {
                    d.watch.forEach { row ->
                        val isActive = row.state == "active"
                        KeyValueColored(
                            row.unit, row.state,
                            valueColor = if (isActive) LocalPandaColors.current.green
                                         else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (d.manageable.isNotEmpty()) {
                items(d.manageable.size) { i ->
                    val unit = d.manageable[i]
                    PandaCard(title = unit, accent = LocalPandaColors.current.yellow) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            listOf("start", "stop", "restart").forEach { act ->
                                androidx.compose.material3.OutlinedButton(
                                    onClick = { pending = unit to act },
                                    enabled = !exec.busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text(act) }
                            }
                        }
                    }
                }
            } else {
                item {
                    EmptyState("manageable está vacío en el config del backend " +
                        "— agrega units para tener acciones aquí.")
                }
            }
        }
        item { io.github.pandaakira.apppanda.ui.components.ActionResultBanner(exec) }
    }

    pending?.let { (unit, act) ->
        io.github.pandaakira.apppanda.ui.components.ConfirmDialog(
            title = pandaDeco("${act.uppercase()} $unit"),
            message = "¿Ejecutar `systemctl $act $unit`?",
            confirmLabel = act,
            onConfirm = {
                exec.run("$act $unit") { it.serviceAction(unit, act) }
                scope.launch { kotlinx.coroutines.delay(700); refresh++ }
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun KeyValueColored(k: String, v: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            k,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            v,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
}
