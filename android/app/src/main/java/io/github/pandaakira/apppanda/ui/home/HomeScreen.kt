package io.github.pandaakira.apppanda.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import io.github.pandaakira.apppanda.ui.theme.PandaGreen
import io.github.pandaakira.apppanda.ui.theme.PandaRed
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.PandaApi
import io.github.pandaakira.apppanda.data.models.SseEvent
import io.github.pandaakira.apppanda.data.models.SystemStatus
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.KeyValue
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.StatBar
import io.github.pandaakira.apppanda.ui.theme.PandaCyan
import io.github.pandaakira.apppanda.ui.theme.PandaMagenta
import io.github.pandaakira.apppanda.ui.theme.PandaYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(app: PandaApp, onGoSetup: () -> Unit) {
    val api by app.repository.api.collectAsState()
    var status by remember { mutableStateOf<SystemStatus?>(null) }
    var liveCpu by remember { mutableStateOf<Double?>(null) }
    var liveRam by remember { mutableStateOf<Double?>(null) }
    var liveGpu by remember { mutableStateOf<Double?>(null) }
    var liveCpuTemp by remember { mutableStateOf<Double?>(null) }
    var liveGpuTemp by remember { mutableStateOf<Double?>(null) }
    val events = remember { mutableStateListOf<SseEvent>() }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api) {
        if (api == null) return@LaunchedEffect
        scope.launch {
            try {
                status = withContext(Dispatchers.IO) { api!!.systemStatus() }
                error = null
            } catch (e: Exception) {
                error = e.message?.take(140) ?: e::class.simpleName
            }
        }
    }

    LaunchedEffect(api) {
        app.repository.events.collect { evt ->
            when (evt.type) {
                "metric_tick" -> {
                    evt.cpuPct?.let { liveCpu = it }
                    evt.ramPct?.let { liveRam = it }
                    evt.gpuPct?.let { liveGpu = it }
                    evt.cpuTemp?.let { liveCpuTemp = it }
                    evt.gpuTemp?.let { liveGpuTemp = it }
                }
                else -> {
                    events.add(0, evt)
                    if (events.size > 30) events.removeAt(events.lastIndex)
                }
            }
        }
    }

    if (api == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Text(
                "Sin backend configurado",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGoSetup) { Text("Configurar ahora") }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            HomeHeader(app = app, hostname = status?.hostname)
            status?.let {
                Text(
                    "${it.kernel}  ·  uptime ${fmtUptime(it.uptimeS)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        error?.let {
            item {
                PandaCard(title = "ERROR", accent = MaterialTheme.colorScheme.error) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        item {
            PandaCard(title = "LIVE :: CPU + RAM", accent = PandaYellow) {
                StatBar("CPU", liveCpu ?: status?.cpu?.pct ?: 0.0)
                StatBar("RAM", liveRam ?: status?.ram?.pct ?: 0.0)
                status?.let {
                    Spacer(Modifier.height(4.dp))
                    KeyValue("Cores", it.cpu.cores.toString())
                    KeyValue("Load 1m", "%.2f".format(it.load.m1))
                    KeyValue("RAM total", "%.1f GB".format(it.ram.totalG))
                }
            }
        }

        item {
            PandaCard(title = "LIVE :: GPU + TEMPS", accent = PandaMagenta) {
                StatBar("GPU", liveGpu ?: 0.0)
                Spacer(Modifier.height(4.dp))
                KeyValue("Temp CPU", liveCpuTemp?.let { "%.1f °C".format(it) } ?: "—")
                KeyValue("Temp GPU", liveGpuTemp?.let { "%.1f °C".format(it) } ?: "—")
            }
        }

        item {
            PandaCard(title = "EVENTS :: ${events.size}", accent = PandaCyan) {
                if (events.isEmpty()) {
                    EmptyState("Esperando eventos del backend (alerts, boot, services failed…)")
                } else {
                    events.take(8).forEach { evt ->
                        Text(
                            "${evt.type.padEnd(16)} ${formatEvent(evt)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 2.dp).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private fun fmtUptime(secs: Double): String {
    val s = secs.toLong()
    val d = s / 86400
    val h = (s % 86400) / 3600
    val m = (s % 3600) / 60
    return if (d > 0) "${d}d ${h}h ${m}m" else "${h}h ${m}m"
}

@Composable
private fun HomeHeader(app: PandaApp, hostname: String?) {
    val lastEventAt by app.repository.lastEventAt.collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(2_000)
        }
    }
    val connected = lastEventAt > 0 && (nowMs - lastEventAt) < 30_000
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(if (connected) PandaGreen else PandaRed)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            ">> PANDA // ${hostname ?: "—"}",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun formatEvent(evt: SseEvent): String = when (evt.type) {
    "service_failed" -> evt.unit.orEmpty()
    "session_new" -> evt.session.orEmpty()
    "boot" -> "${evt.hostname ?: "?"} (${evt.bootId?.take(8)})"
    "resume" -> "gap ≈ ${evt.gapS?.toInt() ?: "?"} s"
    "alert" -> "${evt.key ?: "?"} = ${evt.valueStr ?: evt.value}"
    else -> ""
}
