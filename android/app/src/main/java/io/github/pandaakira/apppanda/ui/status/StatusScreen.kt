package io.github.pandaakira.apppanda.ui.status
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.DiskResponse
import io.github.pandaakira.apppanda.data.models.GpusResponse
import io.github.pandaakira.apppanda.data.models.NetStatus
import io.github.pandaakira.apppanda.data.models.SystemStatus
import io.github.pandaakira.apppanda.data.models.TempsResponse
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.KeyValue
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.StatBar
import io.github.pandaakira.apppanda.ui.more.LogsScreen
import io.github.pandaakira.apppanda.ui.more.ProcessesScreen
import io.github.pandaakira.apppanda.ui.trends.TrendsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val tabs = listOf("Sistema", "Disco", "Red", "Temps", "GPU", "Procesos", "Logs", "Histórico")

@Composable
fun MonitorScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var tab by remember { mutableStateOf(0) }

    if (api == null) {
        EmptyState("Configura el backend en Ajustes.")
        return
    }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(label, style = MaterialTheme.typography.labelLarge) },
                )
            }
        }

        when (tab) {
            0 -> SystemTab(app)
            1 -> DiskTab(app)
            2 -> NetTab(app)
            3 -> TempsTab(app)
            4 -> GpuTab(app)
            5 -> ProcessesScreen(app)
            6 -> LogsScreen(app)
            7 -> TrendsScreen(app)
        }
    }
}

@Composable
private fun SystemTab(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var data by remember { mutableStateOf<SystemStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api) {
        api?.let {
            scope.launch {
                try {
                    data = withContext(Dispatchers.IO) { it.systemStatus() }
                    error = null
                } catch (e: Exception) {
                    error = e.message ?: e::class.simpleName
                }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { item { ErrorCard(it) } }
        data?.let { d ->
            item {
                PandaCard(title = "HOST", accent = LocalPandaColors.current.yellow) {
                    KeyValue("Hostname", d.hostname)
                    KeyValue("Kernel", d.kernel)
                    KeyValue("Uptime", "%.0f s".format(d.uptimeS))
                    KeyValue("Boot", d.bootId.take(8))
                }
            }
            item {
                PandaCard(title = "CPU + LOAD", accent = LocalPandaColors.current.magenta) {
                    StatBar("CPU", d.cpu.pct)
                    KeyValue("Cores", d.cpu.cores.toString())
                    KeyValue("Load 1m / 5m / 15m",
                        "%.2f / %.2f / %.2f".format(d.load.m1, d.load.m5, d.load.m15))
                }
            }
            item {
                PandaCard(title = "RAM", accent = LocalPandaColors.current.cyan) {
                    StatBar("RAM", d.ram.pct)
                    KeyValue("Total", "%.2f GB".format(d.ram.totalG))
                    KeyValue("Usada", "%.2f GB".format(d.ram.usedG))
                }
            }
        } ?: item { Loading() }
    }
}

@Composable
private fun DiskTab(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var data by remember { mutableStateOf<DiskResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api) {
        api?.let {
            scope.launch {
                try { data = withContext(Dispatchers.IO) { it.disk() }; error = null }
                catch (e: Exception) { error = e.message }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { item { ErrorCard(it) } }
        data?.mounts?.forEach { m ->
            item {
                PandaCard(title = m.mount, accent = LocalPandaColors.current.green) {
                    StatBar("Uso", m.pct)
                    KeyValue("Origen", m.source)
                    KeyValue("Total", "%.1f GB".format(m.sizeG))
                    KeyValue("Usado", "%.1f GB".format(m.usedG))
                }
            }
        }
        if (data?.mounts.isNullOrEmpty() && error == null) item { Loading() }
    }
}

@Composable
private fun NetTab(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var data by remember { mutableStateOf<NetStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api) {
        api?.let {
            scope.launch {
                try { data = withContext(Dispatchers.IO) { it.net() }; error = null }
                catch (e: Exception) { error = e.message }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { item { ErrorCard(it) } }
        data?.let { d ->
            d.interfaces.forEach { (name, info) ->
                item {
                    PandaCard(title = "IFACE :: $name", accent = LocalPandaColors.current.cyan) {
                        KeyValue("RX", fmtBps(info.rxBps))
                        KeyValue("TX", fmtBps(info.txBps))
                        KeyValue("RX total", fmtBytes(info.rxTotal.toDouble()))
                        KeyValue("TX total", fmtBytes(info.txTotal.toDouble()))
                    }
                }
            }
            if (d.pings.isNotEmpty()) {
                item {
                    PandaCard(title = "PING", accent = LocalPandaColors.current.yellow) {
                        d.pings.forEach { p ->
                            KeyValue(
                                p.host,
                                if (p.ok) "%.1f ms".format(p.rttMs ?: 0.0) else "down",
                            )
                        }
                    }
                }
            }
        } ?: if (error == null) item { Loading() } else Unit
    }
}

@Composable
private fun TempsTab(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var data by remember { mutableStateOf<TempsResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api) {
        api?.let {
            scope.launch {
                try { data = withContext(Dispatchers.IO) { it.temps() }; error = null }
                catch (e: Exception) { error = e.message }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { item { ErrorCard(it) } }
        val temps = data?.temps.orEmpty()
        if (temps.isNotEmpty()) {
            val grouped = temps.groupBy { it.chip }
            grouped.forEach { (chip, rows) ->
                item {
                    PandaCard(title = chip, accent = LocalPandaColors.current.magenta) {
                        rows.forEach { r ->
                            KeyValue(r.label, "%.1f °C".format(r.c))
                        }
                    }
                }
            }
        } else if (error == null) item { Loading() }
    }
}

@Composable
private fun GpuTab(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var data by remember { mutableStateOf<GpusResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api, refresh) {
        api?.let {
            scope.launch {
                try {
                    data = withContext(Dispatchers.IO) { it.gpu() }
                    error = null
                } catch (e: Exception) {
                    error = e.message ?: e::class.simpleName
                }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            androidx.compose.material3.OutlinedButton(
                onClick = { refresh++ },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("↻ Refrescar") }
        }
        error?.let { item { ErrorCard(it) } }
        data?.gpus?.let { gpus ->
            if (gpus.isEmpty()) {
                item { EmptyState("No se detectaron GPUs en /sys/class/drm/.") }
            } else {
                gpus.forEachIndexed { idx, g ->
                    item {
                        PandaCard(
                            title = "${g.vendor} :: ${g.name}",
                            accent = if (idx == 0) LocalPandaColors.current.yellow else LocalPandaColors.current.magenta,
                        ) {
                            // Siempre mostrar uso y VRAM, incluso si son 0
                            StatBar("Uso", g.pct ?: 0.0)
                            g.memPct?.let { StatBar("VRAM", it) }
                            KeyValue("Temp", g.tempC?.let { "%.1f °C".format(it) } ?: "—")
                            KeyValue("Power", g.powerW?.let { "%.1f W".format(it) } ?: "—")
                            KeyValue("Fan", g.fanRpm?.let { "$it rpm" } ?: "—")
                            g.memTotalG?.let { tot ->
                                KeyValue(
                                    "VRAM",
                                    "%.2f / %.2f GB".format(g.memUsedG ?: 0.0, tot),
                                )
                            }
                        }
                    }
                }
            }
        } ?: if (error == null) item { Loading() } else Unit
    }
}

@Composable
private fun Loading() {
    EmptyState("Cargando…")
}

private fun fmtBps(v: Double): String = when {
    v >= 1_000_000 -> "%.2f MB/s".format(v / 1_048_576)
    v >= 1_000 -> "%.1f KB/s".format(v / 1024)
    else -> "%.0f B/s".format(v)
}

private fun fmtBytes(v: Double): String = when {
    v >= 1_073_741_824 -> "%.2f GB".format(v / 1_073_741_824)
    v >= 1_048_576 -> "%.1f MB".format(v / 1_048_576)
    v >= 1024 -> "%.1f KB".format(v / 1024)
    else -> "%.0f B".format(v)
}
