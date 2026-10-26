package io.github.pandaakira.apppanda.ui.more

import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.pandaDeco
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import kotlinx.coroutines.launch

// ─── Updates ─────────────────────────────────────────────────────────────────

@Composable
fun UpdatesScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var data by remember {
        mutableStateOf<io.github.pandaakira.apppanda.data.models.UpdatesResponse?>(null)
    }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var pendingApply by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exec = io.github.pandaakira.apppanda.ui.components.rememberActionExecutor { api }

    io.github.pandaakira.apppanda.ui.components.PollingEffect(
        api = api, key = refresh, intervalMs = 60_000,
        onResult = { data = it }, onError = { error = it },
    ) { it.updates() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ScreenHeader("UPDATES :: checkupdates") }
        error?.let { item { ErrorCard(it) } }
        data?.let { d ->
            item {
                PandaCard(
                    title = "PENDING :: ${d.count}",
                    accent = if (d.count == 0) LocalPandaColors.current.green else LocalPandaColors.current.orange,
                ) {
                    if (d.count == 0) {
                        Text("Sistema al día.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalPandaColors.current.green)
                    } else {
                        d.packages.forEach { pkg ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(pkg.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f))
                                Text("${pkg.from} → ${pkg.to}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.Button(
                            onClick = { pendingApply = true },
                            enabled = !exec.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Aplicar ${d.count} updates") }
                    }
                }
            }
        }
        item { io.github.pandaakira.apppanda.ui.components.ActionResultBanner(exec) }
    }

    if (pendingApply) {
        io.github.pandaakira.apppanda.ui.components.ConfirmDialog(
            title = pandaDeco("APPLY UPDATES"),
            message = "Esto arranca pacman-update.service (oneshot) en el PC. " +
                      "El job corre en background; vuelve a abrir esta pantalla " +
                      "después para ver el resultado.",
            confirmLabel = "aplicar",
            onConfirm = {
                exec.run("apply updates") { it.applyUpdates() }
                scope.launch {
                    kotlinx.coroutines.delay(2000); refresh++
                }
                pendingApply = false
            },
            onDismiss = { pendingApply = false },
        )
    }
}
