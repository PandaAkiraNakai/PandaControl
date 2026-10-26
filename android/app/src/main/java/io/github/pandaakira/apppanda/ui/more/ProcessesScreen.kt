package io.github.pandaakira.apppanda.ui.more

import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.pandaDeco
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import kotlinx.coroutines.launch

// ─── Procesos ────────────────────────────────────────────────────────────────

@Composable
fun ProcessesScreen(app: PandaApp) {
    var sort by remember { mutableStateOf("cpu") }
    var refresh by remember { mutableStateOf(0) }
    var pending by remember {
        mutableStateOf<io.github.pandaakira.apppanda.data.models.ProcessRow?>(null)
    }
    val api by app.repository.api.collectAsState()
    var data by remember(sort, refresh) {
        mutableStateOf<io.github.pandaakira.apppanda.data.models.ProcessesResponse?>(null)
    }
    var error by remember(sort, refresh) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val exec = io.github.pandaakira.apppanda.ui.components.rememberActionExecutor { api }

    io.github.pandaakira.apppanda.ui.components.PollingEffect(
        api = api, key = sort to refresh, intervalMs = 8_000,
        onResult = { data = it }, onError = { error = it },
    ) { it.processes(sort = sort, limit = 20) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ScreenHeader("PROCESSES :: TOP", "tap a un proceso para kill (TERM)") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("cpu", "ram").forEach { s ->
                    FilterChip(
                        selected = sort == s,
                        onClick = { sort = s },
                        label = { Text(s.uppercase()) },
                    )
                }
            }
        }
        error?.let { item { ErrorCard(it) } }
        data?.let { d ->
            item {
                PandaCard(title = "TOP ${d.sort.uppercase()}", accent = LocalPandaColors.current.yellow) {
                    MonoRow("PID", "USER", "%CPU", "%RAM", "COMM",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    d.rows.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !exec.busy) { pending = r }
                                .padding(vertical = 2.dp),
                        ) {
                            val mono = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace)
                            Text(r.pid.padStart(6).take(6), style = mono,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(56.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(r.user.padEnd(10).take(10), style = mono,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(84.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(r.cpuPct.padStart(5).take(5), style = mono,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(40.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(r.ramPct.padStart(5).take(5), style = mono,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(40.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(r.comm.take(20), style = mono,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        item { io.github.pandaakira.apppanda.ui.components.ActionResultBanner(exec) }
    }

    pending?.let { p ->
        io.github.pandaakira.apppanda.ui.components.ConfirmDialog(
            title = pandaDeco("KILL ${p.pid}"),
            message = "Enviar SIGTERM al proceso `${p.comm}` (PID ${p.pid}, " +
                      "user ${p.user})?",
            confirmLabel = "kill",
            onConfirm = {
                exec.run("kill ${p.pid}") { it.killProcess(p.pid) }
                scope.launch { kotlinx.coroutines.delay(700); refresh++ }
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun MonoRow(c1: String, c2: String, c3: String, c4: String, c5: String,
                    color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        Text(c1.padStart(6).take(6), style = mono, color = color, modifier = Modifier.width(56.dp))
        Spacer(Modifier.width(4.dp))
        Text(c2.padEnd(10).take(10), style = mono, color = color, modifier = Modifier.width(84.dp))
        Spacer(Modifier.width(4.dp))
        Text(c3.padStart(5).take(5), style = mono, color = color, modifier = Modifier.width(40.dp))
        Spacer(Modifier.width(4.dp))
        Text(c4.padStart(5).take(5), style = mono, color = color, modifier = Modifier.width(40.dp))
        Spacer(Modifier.width(4.dp))
        Text(c5.take(20), style = mono, color = color)
    }
}
