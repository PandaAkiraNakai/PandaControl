package io.github.pandaakira.apppanda.ui.more

import io.github.pandaakira.apppanda.ui.theme.LocalPandaShapes
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.pandaDeco
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import kotlinx.coroutines.launch

// ─── Displays (niri + KDE Plasma) ────────────────────────────────────────────

@Composable
fun DisplaysScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var data by remember {
        mutableStateOf<io.github.pandaakira.apppanda.data.models.ScreensResponse?>(null)
    }
    var scenes by remember {
        mutableStateOf<List<io.github.pandaakira.apppanda.data.models.Scene>>(emptyList())
    }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val exec = io.github.pandaakira.apppanda.ui.components.rememberActionExecutor { api }

    io.github.pandaakira.apppanda.ui.components.PollingEffect(
        api = api, key = refresh, intervalMs = 10_000,
        onResult = { data = it }, onError = { error = it },
    ) { it.screens() }

    io.github.pandaakira.apppanda.ui.components.PollingEffect(
        api = api, intervalMs = 30_000,
        onResult = { scenes = it.scenes }, onError = {},
    ) { it.scenes() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        val compositorLabel = when (data?.compositor) {
            "kde" -> "plasma"
            "niri" -> "niri"
            "cosmic" -> "cosmic"
            "hyprland" -> "hyprland"
            else -> "?"
        }
        item { ScreenHeader("DISPLAYS :: $compositorLabel", "tap output = toggle on/off") }

        item { CaffeineControl(api, exec) }

        // Escenas: presets que combinan outputs + foco + audio de un toque.
        if (scenes.isNotEmpty()) {
            item { ScenesCard(scenes, exec) { scope.launch { kotlinx.coroutines.delay(900); refresh++ } } }
        }

        error?.let { item { ErrorCard(it) } }
        data?.error?.let { item { ErrorCard(it) } }
        data?.outputs?.let { outs ->
            items(outs.size) { i ->
                val o = outs[i]
                val accent = if (o.on) LocalPandaColors.current.green else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(LocalPandaShapes.current.corner))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(LocalPandaShapes.current.border, accent.copy(alpha = 0.5f),
                            RoundedCornerShape(LocalPandaShapes.current.corner))
                        .clickable(enabled = !exec.busy) {
                            exec.run("${o.name} → ${if (o.on) "OFF" else "ON"}") {
                                it.setScreen(o.name, !o.on)
                            }
                            scope.launch {
                                kotlinx.coroutines.delay(800); refresh++
                            }
                        }.padding(16.dp),
                ) {
                    Text(pandaDeco(o.name), style = MaterialTheme.typography.labelSmall,
                        color = accent)
                    Text(o.label, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(if (o.on) pandaDeco("ENCENDIDA") else pandaDeco("APAGADA"),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item {
            PandaCard(title = "DPMS :: global", accent = LocalPandaColors.current.yellow) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)) {
                    androidx.compose.material3.Button(
                        onClick = {
                            exec.run("DPMS ON") { it.setDpms(true) }
                            scope.launch { kotlinx.coroutines.delay(800); refresh++ }
                        },
                        enabled = !exec.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("ON") }
                    androidx.compose.material3.Button(
                        onClick = {
                            exec.run("DPMS OFF") { it.setDpms(false) }
                            scope.launch { kotlinx.coroutines.delay(800); refresh++ }
                        },
                        enabled = !exec.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("OFF") }
                }
            }
        }
        item { io.github.pandaakira.apppanda.ui.components.ActionResultBanner(exec) }
    }
}
