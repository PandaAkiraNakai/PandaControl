package io.github.pandaakira.apppanda.ui.more

import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─── VPS ─────────────────────────────────────────────────────────────────────

@Composable
fun VpsScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var hosts by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var loadingSummary by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    io.github.pandaakira.apppanda.ui.components.PollingEffect(
        api = api, intervalMs = 30_000,
        onResult = { hosts = it.hosts.map { h -> h.alias } }, onError = { error = it },
    ) { it.vpsList() }

    LaunchedEffect(selected) {
        val current = api ?: return@LaunchedEffect
        val alias = selected ?: return@LaunchedEffect
        loadingSummary = true
        summary = null
        try {
            val resp = withContext(Dispatchers.IO) { current.vpsSummary(alias) }
            summary = resp.error?.let { "ERROR: $it" } ?: resp.output
        } catch (e: Exception) {
            summary = "ERROR: ${e.message ?: e::class.simpleName}"
        } finally {
            loadingSummary = false
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ScreenHeader("VPS", "${hosts.size} hosts configurados") }
        when {
            api == null -> item { EmptyState("Configura el backend en Ajustes.") }
            error != null -> item { ErrorCard(error!!) }
            hosts.isEmpty() -> item { EmptyState("Sin VPS configurados en el backend.") }
            else -> {
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        hosts.forEach { h ->
                            FilterChip(
                                selected = selected == h,
                                onClick = { selected = h },
                                label = { Text(h) },
                            )
                        }
                    }
                }
                selected?.let { alias ->
                    item {
                        PandaCard(title = "SSH :: $alias", accent = LocalPandaColors.current.cyan) {
                            if (loadingSummary) {
                                Text("Cargando…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    summary.orEmpty(),
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
    }
}
