package io.github.pandaakira.apppanda.ui.quick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.AppEntry
import io.github.pandaakira.apppanda.data.models.AudioResponse
import io.github.pandaakira.apppanda.data.models.Scene
import io.github.pandaakira.apppanda.ui.components.ActionResultBanner
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.PollingEffect
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.components.rememberActionExecutor
import io.github.pandaakira.apppanda.ui.more.AppTile
import io.github.pandaakira.apppanda.ui.more.CaffeineControl
import io.github.pandaakira.apppanda.ui.more.MasterVolumeCard
import io.github.pandaakira.apppanda.ui.more.ScenesCard
import io.github.pandaakira.apppanda.ui.more.rememberAppIcons
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import kotlinx.coroutines.launch

/** Cuántas apps (en el orden del config del backend) muestra Rápido. */
private const val QUICK_APPS = 6

/**
 * Pestaña Rápido: lo de todos los días en una sola pantalla — caffeine,
 * volumen, las primeras apps del config y las escenas de monitores. Reusa
 * las tarjetas de Pantallas, Reproductor y Apps.
 */
@Composable
fun QuickScreen(app: PandaApp, onNavigate: (String) -> Unit) {
    val api by app.repository.api.collectAsState()
    val exec = rememberActionExecutor { api }
    val scope = rememberCoroutineScope()

    var audio by remember { mutableStateOf<AudioResponse?>(null) }
    var audioRefresh by remember { mutableStateOf(0) }
    PollingEffect(
        api = api, key = audioRefresh, intervalMs = 10_000,
        onResult = { audio = it }, onError = {},
    ) { runCatching { it.audio() }.getOrNull() }

    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    PollingEffect(
        api = api, intervalMs = 60_000,
        onResult = { apps = it.apps }, onError = {},
    ) { it.apps() }
    val quickApps = apps.orEmpty().take(QUICK_APPS)
    val icons = rememberAppIcons(api, quickApps)

    var scenes by remember { mutableStateOf<List<Scene>>(emptyList()) }
    PollingEffect(
        api = api, intervalMs = 60_000,
        onResult = { scenes = it.scenes }, onError = {},
    ) { it.scenes() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ScreenHeader("RÁPIDO", "lo de todos los días") }
        if (api == null) {
            item { EmptyState("Configura el backend en Ajustes.") }
            return@LazyColumn
        }

        item { CaffeineControl(api, exec) }

        audio?.master?.takeIf { it.error == null }?.let { master ->
            item { MasterVolumeCard(master, exec) { audioRefresh++ } }
        }

        if (quickApps.isNotEmpty()) {
            item {
                PandaCard(title = "APPS", accent = LocalPandaColors.current.cyan) {
                    quickApps.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { a ->
                                Box(Modifier.weight(1f)) {
                                    AppTile(a, icon = icons[a.name], enabled = !exec.busy) {
                                        exec.run("Abrir ${a.label}") { it.launchApp(a.name) }
                                    }
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (apps.orEmpty().size > QUICK_APPS) {
                        Row(Modifier, verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onNavigate("apps") }) {
                                Text("ver todas (${apps.orEmpty().size})")
                            }
                        }
                    }
                }
            }
        }

        if (scenes.isNotEmpty()) {
            item {
                ScenesCard(scenes, exec) {
                    scope.launch { kotlinx.coroutines.delay(900); audioRefresh++ }
                }
            }
        }

        item { ActionResultBanner(exec) }
    }
}
