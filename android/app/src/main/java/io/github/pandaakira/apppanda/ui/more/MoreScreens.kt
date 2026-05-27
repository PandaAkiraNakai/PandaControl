package io.github.pandaakira.apppanda.ui.more
import io.github.pandaakira.apppanda.ui.theme.LocalPandaShapes
import io.github.pandaakira.apppanda.ui.theme.PandaIcons
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.MediaStatus
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.KeyValue
import io.github.pandaakira.apppanda.ui.components.pandaDeco
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.RemoteScreen
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.components.StatBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── MoreScreen: grid de accesos a las pantallas extra ───────────────────────

private data class ModuleEntry(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val color: Color,
)

@Composable
fun ModulesScreen(onNavigate: (String) -> Unit) {
    val modules = listOf(
        ModuleEntry("services", "Servicios", PandaIcons.tune, LocalPandaColors.current.cyan),
        ModuleEntry("updates", "Actualizar", PandaIcons.systemUpdate, LocalPandaColors.current.green),
        ModuleEntry("power", "Energía", PandaIcons.powerSettingsNew,
            MaterialTheme.colorScheme.error),
        ModuleEntry("files", "Archivos", PandaIcons.folder, LocalPandaColors.current.green),
        ModuleEntry("network", "Red LAN", PandaIcons.wifi, LocalPandaColors.current.green),
        ModuleEntry("vps", "VPS", PandaIcons.cloud, LocalPandaColors.current.yellow),
        ModuleEntry("terminal", "Terminal", PandaIcons.dns, LocalPandaColors.current.cyan),
        ModuleEntry("temas", "Temas", PandaIcons.palette, LocalPandaColors.current.magenta),
    )

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                ScreenHeader("SISTEMA", "administración de la máquina")
            }
            IconButton(onClick = { onNavigate("settings") }) {
                Icon(
                    PandaIcons.settings,
                    contentDescription = "Ajustes",
                    tint = LocalPandaColors.current.cyan,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            gridItems(modules) { entry ->
                ModuleCard(entry) { onNavigate(entry.route) }
            }
        }
    }
}

@Composable
private fun ModuleCard(entry: ModuleEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(LocalPandaShapes.current.corner))
            .background(MaterialTheme.colorScheme.surface)
            .border(LocalPandaShapes.current.border, entry.color.copy(alpha = 0.5f), RoundedCornerShape(LocalPandaShapes.current.corner))
            .clickable { onClick() }
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(
            entry.icon,
            contentDescription = entry.label,
            tint = entry.color,
        )
        Text(
            entry.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

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

    LaunchedEffect(api, sort, refresh) {
        val current = api ?: return@LaunchedEffect
        scope.launch {
            try {
                data = withContext(Dispatchers.IO) {
                    current.processes(sort = sort, limit = 20)
                }
                error = null
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            }
        }
    }

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

// ─── Logs ────────────────────────────────────────────────────────────────────

@Composable
fun LogsScreen(app: PandaApp) {
    var priority by remember { mutableStateOf("err") }
    RemoteScreen(
        app = app,
        title = "LOGS :: journalctl",
        subtitle = "priority = $priority · n = 50",
        refreshKey = priority,
        fetch = { it.logs(priority = priority, n = 50) },
    ) { data ->
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("emerg", "alert", "crit", "err", "warning", "notice", "info")
                    .forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p) },
                        )
                    }
            }
        }
        item {
            PandaCard(title = data.priority.uppercase(), accent = LocalPandaColors.current.magenta) {
                Text(
                    data.output.ifBlank { "(sin entradas)" },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

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

    LaunchedEffect(api, refresh) {
        val current = api ?: return@LaunchedEffect
        scope.launch {
            try {
                data = withContext(Dispatchers.IO) { current.updates() }
                error = null
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            }
        }
    }

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
            message = "Esto arranca pacman-update.service (oneshot) en la torre. " +
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

// ─── Displays (niri) ─────────────────────────────────────────────────────────

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

    LaunchedEffect(api, refresh) {
        val current = api ?: return@LaunchedEffect
        scope.launch {
            try {
                data = withContext(Dispatchers.IO) { current.screens() }
                error = null
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            }
        }
    }

    LaunchedEffect(api) {
        val current = api ?: return@LaunchedEffect
        scenes = withContext(Dispatchers.IO) {
            runCatching { current.scenes().scenes }.getOrDefault(emptyList())
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ScreenHeader("DISPLAYS :: niri", "tap output = toggle on/off") }

        // Escenas: presets que combinan outputs + foco + audio de un toque.
        if (scenes.isNotEmpty()) {
            item {
                PandaCard(title = "ESCENAS", accent = LocalPandaColors.current.magenta) {
                    Text("aplica un preset de monitores",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        scenes.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { sc ->
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = {
                                            exec.run("Escena ${sc.label}") { it.applyScene(sc.name) }
                                            scope.launch { kotlinx.coroutines.delay(900); refresh++ }
                                        },
                                        enabled = !exec.busy,
                                        modifier = Modifier.weight(1f),
                                    ) { Text(sc.label, maxLines = 1) }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
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

    LaunchedEffect(api, refresh) {
        val current = api ?: return@LaunchedEffect
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { current.mediaPlayers().players }
                val st = withContext(Dispatchers.IO) {
                    list.associateWith { runCatching { current.mediaStatus(it) }.getOrNull()
                        ?: MediaStatus() }
                }
                players = list
                statuses = st
                error = null
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            }
        }
    }

    // Salida de audio: se carga aparte para que un fallo de pactl no tumbe el
    // reproductor (y viceversa). Comparte el mismo ActionExecutor.
    LaunchedEffect(api, audioRefresh) {
        val current = api ?: return@LaunchedEffect
        scope.launch {
            audio = withContext(Dispatchers.IO) { runCatching { current.audio() }.getOrNull() }
        }
    }

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
                item {
                    val accent = LocalPandaColors.current.magenta
                    val muted = master.muted == true
                    var sliderVol by remember(master.volumePct, master.muted) {
                        mutableStateOf((master.volumePct ?: 0).toFloat())
                    }
                    PandaCard(title = "VOLUMEN", accent = accent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                exec.run(if (muted) "Activar sonido" else "Silenciar") {
                                    it.setMute("toggle")
                                }
                                scope.launch {
                                    kotlinx.coroutines.delay(300); audioRefresh++
                                }
                            }) {
                                Text(
                                    if (muted) "🔇" else "🔊",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                            Slider(
                                value = sliderVol,
                                onValueChange = { sliderVol = it },
                                onValueChangeFinished = {
                                    exec.run("Volumen ${sliderVol.toInt()}%") {
                                        it.setVolume(sliderVol.toInt())
                                    }
                                },
                                valueRange = 0f..150f,
                                enabled = !muted && !exec.busy,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${sliderVol.toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
                                        else accent,
                                modifier = Modifier.width(52.dp),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
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

// ─── Red ─────────────────────────────────────────────────────────────────────

@Composable
fun NetworkScreen(app: PandaApp) = RemoteScreen(
    app = app,
    title = "NET :: LAN",
    fetch = { it.netNeighbors() },
) { data ->
    item {
        PandaCard(title = "GATEWAY", accent = LocalPandaColors.current.yellow) {
            Text(
                data.gateway.ifBlank { "(no detectado)" },
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    item {
        PandaCard(title = "NEIGHBORS :: ${data.neighbors.size}", accent = LocalPandaColors.current.cyan) {
            data.neighbors.forEach { n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    val mono = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace)
                    Text(n.ip, style = mono, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(120.dp))
                    Text(n.dev, style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp))
                    Text(
                        n.state,
                        style = mono,
                        color = when (n.state) {
                            "REACHABLE" -> LocalPandaColors.current.green
                            "STALE" -> LocalPandaColors.current.yellow
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    n.mac,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

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

    LaunchedEffect(api) {
        val current = api ?: return@LaunchedEffect
        scope.launch {
            try {
                hosts = withContext(Dispatchers.IO) {
                    current.vpsList().hosts.map { it.alias }
                }
                error = null
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            }
        }
    }

    LaunchedEffect(selected) {
        val current = api ?: return@LaunchedEffect
        val alias = selected ?: return@LaunchedEffect
        loadingSummary = true
        summary = null
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { current.vpsSummary(alias) }
                summary = resp.error?.let { "ERROR: $it" } ?: resp.output
            } catch (e: Exception) {
                summary = "ERROR: ${e.message ?: e::class.simpleName}"
            } finally {
                loadingSummary = false
            }
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

// ─── Juegos Steam ────────────────────────────────────────────────────────────

@Composable
fun GamesScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var games by remember { mutableStateOf<List<io.github.pandaakira.apppanda.data.models.Game>?>(null) }
    var useGamescope by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf<io.github.pandaakira.apppanda.data.models.RunningGame?>(null) }
    var runningRefresh by remember { mutableStateOf(0) }
    var confirmClose by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val exec = io.github.pandaakira.apppanda.ui.components.rememberActionExecutor { api }

    LaunchedEffect(api) {
        val current = api ?: return@LaunchedEffect
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { current.games() }
                games = resp.games
                useGamescope = resp.useGamescope
                error = null
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            }
        }
    }

    LaunchedEffect(api, runningRefresh) {
        val current = api ?: return@LaunchedEffect
        running = withContext(Dispatchers.IO) {
            runCatching { current.runningGame().running }.getOrNull()
        }
    }

    if (confirmClose) {
        io.github.pandaakira.apppanda.ui.components.ConfirmDialog(
            title = "¿Cerrar el juego?",
            message = "Se enviará SIGTERM a «${running?.name ?: "el juego"}». Podrías perder progreso sin guardar.",
            confirmLabel = "Cerrar",
            onConfirm = {
                confirmClose = false
                exec.run("Cerrar ${running?.name?.take(18) ?: "juego"}") { it.closeGame() }
                scope.launch { kotlinx.coroutines.delay(1500); runningRefresh++ }
            },
            onDismiss = { confirmClose = false },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        ScreenHeader(
            "STEAM :: library",
            "Gamescope ${if (useGamescope) "ON" else "OFF"} · ${games?.size ?: "—"} juegos",
        )
        Spacer(Modifier.height(8.dp))
        running?.let { r ->
            PandaCard(title = "EN CURSO", accent = LocalPandaColors.current.green) {
                Text(r.name, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Button(
                    onClick = { confirmClose = true },
                    enabled = !exec.busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Cerrar juego") }
            }
            Spacer(Modifier.height(8.dp))
        }
        when {
            api == null -> EmptyState("Configura el backend en Ajustes.")
            error != null -> ErrorCard(error!!)
            games == null -> EmptyState("Cargando…")
            games!!.isEmpty() -> EmptyState("Sin juegos detectados en la biblioteca Steam.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                gridItems(games!!) { g ->
                    GameTile(g, enabled = !exec.busy) {
                        exec.run("Steam → ${g.name.take(20)}") { it.launchGame(g.appid) }
                    }
                }
            }
        }
        io.github.pandaakira.apppanda.ui.components.ActionResultBanner(exec)
    }
}

@Composable
private fun GameTile(
    g: io.github.pandaakira.apppanda.data.models.Game,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(LocalPandaShapes.current.corner))
            .background(MaterialTheme.colorScheme.surface)
            .border(LocalPandaShapes.current.border, LocalPandaColors.current.magenta.copy(alpha = 0.5f), RoundedCornerShape(LocalPandaShapes.current.corner))
            .clickable(enabled = enabled) { onClick() }
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            pandaDeco("${g.appid}"),
            style = MaterialTheme.typography.labelSmall,
            color = LocalPandaColors.current.magenta,
        )
        Text(
            g.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
        )
    }
}

// ─── Apps GUI ────────────────────────────────────────────────────────────────

@Composable
fun AppsScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var apps by remember {
        mutableStateOf<List<io.github.pandaakira.apppanda.data.models.AppEntry>?>(null)
    }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val exec = io.github.pandaakira.apppanda.ui.components.rememberActionExecutor { api }

    LaunchedEffect(api) {
        val current = api ?: return@LaunchedEffect
        scope.launch {
            try {
                apps = withContext(Dispatchers.IO) { current.apps().apps }
                error = null
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        ScreenHeader(
            "APPS :: GUI",
            "tap = lanzar (systemd-run --user)",
        )
        Spacer(Modifier.height(8.dp))
        when {
            api == null -> EmptyState("Configura el backend en Ajustes.")
            error != null -> ErrorCard(error!!)
            apps == null -> EmptyState("Cargando…")
            apps!!.isEmpty() -> EmptyState(
                "Sin apps configuradas. Agrega [apps.firefox] etc. al config del backend.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                gridItems(apps!!) { a ->
                    AppTile(a, enabled = !exec.busy) {
                        exec.run("Launch ${a.name}") { it.launchApp(a.name) }
                    }
                }
            }
        }
        io.github.pandaakira.apppanda.ui.components.ActionResultBanner(exec)
    }
}

@Composable
private fun AppTile(
    a: io.github.pandaakira.apppanda.data.models.AppEntry,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Extraer el primer code point completo (emojis son surrogate pairs en UTF-16
    // y .first() devuelve solo el high surrogate, rompiendo el render).
    val firstCp = a.label.codePointAt(0)
    val firstCpStr = String(Character.toChars(firstCp))
    val isEmoji = firstCp >= 0x2300 || firstCp == 0x25B6
    val (icon, rest) = if (isEmoji) {
        firstCpStr to a.label.substring(firstCpStr.length).trim()
    } else {
        "▶" to a.label
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(LocalPandaShapes.current.corner))
            .background(MaterialTheme.colorScheme.surface)
            .border(LocalPandaShapes.current.border, LocalPandaColors.current.cyan.copy(alpha = 0.5f), RoundedCornerShape(LocalPandaShapes.current.corner))
            .clickable(enabled = enabled) { onClick() }
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // fontFamily explícitamente Default (null) para que el sistema
        // resuelva el fallback a Noto Color Emoji para los glyphs no-ASCII.
        Text(
            icon,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
            ),
        )
        Text(
            rest.ifBlank { a.name },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
        )
    }
}

