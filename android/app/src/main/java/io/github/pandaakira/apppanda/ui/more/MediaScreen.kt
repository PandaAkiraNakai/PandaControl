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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.MediaStatus
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.KeyValue
import io.github.pandaakira.apppanda.ui.components.pandaDeco
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import kotlinx.coroutines.launch

// ─── Media (MPRIS) ───────────────────────────────────────────────────────────

@Composable
fun MediaScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var players by remember { mutableStateOf<List<String>>(emptyList()) }
    var statuses by remember { mutableStateOf<Map<String, MediaStatus>>(emptyMap()) }
    var audio by remember {
        mutableStateOf<io.github.pandaakira.apppanda.data.models.AudioResponse?>(null)
    }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var audioRefresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val exec = io.github.pandaakira.apppanda.ui.components.rememberActionExecutor { api }

    io.github.pandaakira.apppanda.ui.components.PollingEffect(
        api = api, key = refresh, intervalMs = 5_000,
        onResult = { (list, st) -> players = list; statuses = st },
        onError = { error = it },
    ) { current ->
        val list = current.mediaPlayers().players
        val st = list.associateWith {
            runCatching { current.mediaStatus(it) }.getOrNull() ?: MediaStatus()
        }
        list to st
    }

    // Salida de audio: se carga aparte para que un fallo de pactl no tumbe el
    // reproductor (y viceversa). Comparte el mismo ActionExecutor.
    io.github.pandaakira.apppanda.ui.components.PollingEffect(
        api = api, key = audioRefresh, intervalMs = 5_000,
        onResult = { audio = it }, onError = {},
    ) { runCatching { it.audio() }.getOrNull() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ScreenHeader("REPRODUCTOR", "${players.size} players · salida de audio") }
        when {
            api == null -> item { EmptyState("Configura el backend en Ajustes.") }
            error != null -> item { ErrorCard(error!!) }
            players.isEmpty() -> item { EmptyState("Sin reproductores activos.") }
            else -> items(players.size) { i ->
                val p = players[i]
                val s = statuses[p] ?: MediaStatus()
                val accent = when (s.status) {
                    "Playing" -> LocalPandaColors.current.green
                    "Paused" -> LocalPandaColors.current.yellow
                    else -> LocalPandaColors.current.cyan
                }
                PandaCard(title = "${p.take(28)} · ${s.status}", accent = accent) {
                    if (s.title.isNotBlank()) {
                        Text(
                            s.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (s.artist.isNotBlank()) KeyValue("Artista", s.artist)
                    if (s.album.isNotBlank()) KeyValue("Álbum", s.album)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "previous" to "⏮",
                            "seek:-15" to "−15",
                            "play-pause" to "⏯",
                            "seek:+15" to "+15",
                            "next" to "⏭",
                        ).forEach { (act, glyph) ->
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    exec.run("${glyph} ${act.take(10)}") {
                                        it.mediaAction(p, act)
                                    }
                                    scope.launch {
                                        kotlinx.coroutines.delay(400); refresh++
                                    }
                                },
                                enabled = !exec.busy,
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 0.dp, vertical = 8.dp,
                                ),
                            ) {
                                Text(glyph, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            exec.run("⛶ fullscreen") { it.mediaAction(p, "fullscreen") }
                        },
                        enabled = !exec.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("⛶  Pantalla completa (video)")
                    }
                }
            }
        }

        // ─── Salida de audio (fusionado con el reproductor) ───
        if (api != null) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    pandaDeco("SALIDA DE AUDIO"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "tap a un sink = ponerlo por defecto",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Volumen maestro del sink por defecto: slider + mute. El slider
            // usa estado local (sliderVol) para que arrastrar sea fluido y solo
            // manda el POST al soltar (onValueChangeFinished); -1f = "todavía no
            // tocado", así toma el valor real que llega del backend.
            audio?.master?.let { master ->
                item { MasterVolumeCard(master, exec) { audioRefresh++ } }
            }

            // ─── Micrófono ───
            audio?.mic?.takeIf { it.error == null }?.let { mic ->
                item {
                    val accent = LocalPandaColors.current.orange
                    val muted = mic.muted == true
                    var micVol by remember(mic.volumePct, mic.muted) {
                        mutableStateOf((mic.volumePct ?: 0).toFloat())
                    }
                    PandaCard(title = "MICRÓFONO", accent = accent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                exec.run(if (muted) "Activar mic" else "Silenciar mic") {
                                    it.setMicMute("toggle")
                                }
                                scope.launch { kotlinx.coroutines.delay(300); audioRefresh++ }
                            }) {
                                Text(if (muted) "🔇" else "🎙", style = MaterialTheme.typography.titleLarge)
                            }
                            Slider(
                                value = micVol,
                                onValueChange = { micVol = it },
                                onValueChangeFinished = {
                                    exec.run("Mic ${micVol.toInt()}%") { it.setMicVolume(micVol.toInt()) }
                                },
                                valueRange = 0f..150f,
                                enabled = !muted && !exec.busy,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${micVol.toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else accent,
                                modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
                        }
                    }
                }
            }

            // ─── Volumen por aplicación ───
            audio?.apps?.takeIf { it.isNotEmpty() }?.let { apps ->
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(pandaDeco("POR APLICACIÓN"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                items(apps.size) { i ->
                    val app0 = apps[i]
                    val accent = LocalPandaColors.current.cyan
                    val muted = app0.muted
                    var v by remember(app0.id, app0.volumePct, app0.muted) {
                        mutableStateOf((app0.volumePct ?: 0).toFloat())
                    }
                    PandaCard(title = app0.label.take(28), accent = accent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                exec.run(if (muted) "Activar ${app0.label.take(12)}" else "Silenciar ${app0.label.take(12)}") {
                                    it.setAppMute(app0.id, "toggle")
                                }
                                scope.launch { kotlinx.coroutines.delay(300); audioRefresh++ }
                            }) {
                                Text(if (muted) "🔇" else "🔊", style = MaterialTheme.typography.titleLarge)
                            }
                            Slider(
                                value = v,
                                onValueChange = { v = it },
                                onValueChangeFinished = {
                                    exec.run("${app0.label.take(12)} ${v.toInt()}%") {
                                        it.setAppVolume(app0.id, v.toInt())
                                    }
                                },
                                valueRange = 0f..150f,
                                enabled = !muted && !exec.busy,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${v.toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else accent,
                                modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
                        }
                    }
                }
            }

            audio?.error?.let { item { ErrorCard(it) } }
            val sinks = audio?.sinks
            if (sinks.isNullOrEmpty()) {
                item { EmptyState("Sin sinks de audio.") }
            } else {
                items(sinks.size) { i ->
                    val sink = sinks[i]
                    val isDefault = sink.name == audio?.default
                    val accent = if (isDefault) LocalPandaColors.current.green else LocalPandaColors.current.cyan
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(LocalPandaShapes.current.corner))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(LocalPandaShapes.current.border, accent.copy(alpha = 0.4f),
                                RoundedCornerShape(LocalPandaShapes.current.corner))
                            .clickable(enabled = !isDefault && !exec.busy) {
                                exec.run("Audio → ${sink.label.take(20)}") {
                                    it.setAudioSink(sink.name)
                                }
                                scope.launch {
                                    kotlinx.coroutines.delay(600)
                                    audioRefresh++
                                }
                            }.padding(16.dp),
                    ) {
                        Text(pandaDeco("${sink.icon} ${sink.kind.uppercase()}"),
                            style = MaterialTheme.typography.labelSmall, color = accent)
                        Text(sink.label, style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(sink.name,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (isDefault) {
                            Text(pandaDeco("DEFAULT"),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalPandaColors.current.green,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }

        item { io.github.pandaakira.apppanda.ui.components.ActionResultBanner(exec) }
    }
}
