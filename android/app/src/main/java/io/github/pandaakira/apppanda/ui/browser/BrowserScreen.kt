package io.github.pandaakira.apppanda.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.BrowserTab
import io.github.pandaakira.apppanda.data.models.PageLink
import io.github.pandaakira.apppanda.data.models.WebResult
import io.github.pandaakira.apppanda.data.models.YtVideo
import io.github.pandaakira.apppanda.ui.components.ActionResultBanner
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.components.rememberActionExecutor
import io.github.pandaakira.apppanda.ui.theme.PandaCyan
import io.github.pandaakira.apppanda.ui.theme.PandaGreen
import io.github.pandaakira.apppanda.ui.theme.PandaMagenta
import io.github.pandaakira.apppanda.ui.theme.PandaYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BrowserScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    val exec = rememberActionExecutor { api }
    val scope = rememberCoroutineScope()

    var urlText by remember { mutableStateOf("") }
    var ytQuery by remember { mutableStateOf("") }
    var ytResults by remember { mutableStateOf<List<YtVideo>>(emptyList()) }
    var ytBusy by remember { mutableStateOf(false) }
    var ytNote by remember { mutableStateOf<String?>(null) }

    var webResults by remember { mutableStateOf<List<WebResult>>(emptyList()) }
    var webBusy by remember { mutableStateOf(false) }
    var webNote by remember { mutableStateOf<String?>(null) }

    var tabs by remember { mutableStateOf<List<BrowserTab>>(emptyList()) }
    var cdpAvailable by remember { mutableStateOf(true) }
    var tabsBusy by remember { mutableStateOf(false) }
    var selectedTabId by remember { mutableStateOf<String?>(null) }

    var clickText by remember { mutableStateOf("") }
    var typeText by remember { mutableStateOf("") }
    var typeSubmit by remember { mutableStateOf(true) }

    var pageLinks by remember { mutableStateOf<List<PageLink>>(emptyList()) }
    var linksBusy by remember { mutableStateOf(false) }

    fun loadLinks() {
        val current = api ?: return
        val t = selectedTabId ?: return
        linksBusy = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { current.browserLinks(t) }
                pageLinks = r.links
            } catch (_: Exception) {
                pageLinks = emptyList()
            } finally {
                linksBusy = false
            }
        }
    }

    fun refreshTabs() {
        val current = api ?: return
        tabsBusy = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { current.browserTabs() }
                cdpAvailable = r.available
                tabs = r.tabs
                // Mantén una pestaña seleccionada para el control de página.
                if (r.tabs.none { it.id == selectedTabId }) {
                    selectedTabId = r.tabs.firstOrNull()?.id
                }
            } catch (_: Exception) {
                cdpAvailable = false
                tabs = emptyList()
                selectedTabId = null
            } finally {
                tabsBusy = false
            }
        }
    }

    fun searchWeb() {
        val current = api ?: return
        val q = urlText.trim()
        if (q.isBlank()) return
        webBusy = true
        webNote = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { current.webSearch(q) }
                webResults = r.results
                if (r.results.isEmpty()) webNote = "Sin resultados"
            } catch (e: Exception) {
                webNote = "error: ${e.message?.take(80) ?: "fallo"}"
            } finally {
                webBusy = false
            }
        }
    }

    fun searchYoutube() {
        val current = api ?: return
        val q = ytQuery.trim()
        if (q.isBlank()) return
        ytBusy = true
        ytNote = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { current.youtubeSearch(q) }
                ytResults = r.results
                if (r.results.isEmpty()) ytNote = "Sin resultados"
            } catch (e: Exception) {
                ytNote = "error: ${e.message?.take(80) ?: "fallo"}"
            } finally {
                ytBusy = false
            }
        }
    }

    LaunchedEffect(api) { refreshTabs() }

    Column(
        Modifier
            .fillMaxSize()
            // imePadding ANTES del scroll: encoge el viewport cuando aparece el
            // teclado, así Compose hace auto-scroll del campo enfocado hacia la
            // zona visible en vez de dejarlo tapado.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ScreenHeader("NAVEGADOR", "Brave vía CDP · buscar web · YouTube · pestañas · control")
        Spacer(Modifier.height(12.dp))

        ActionResultBanner(exec)
        Spacer(Modifier.height(12.dp))

        // ─── Abrir o buscar (input único, dos acciones) ──────────────────
        PandaCard(title = "ABRIR O BUSCAR", accent = PandaGreen) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                label = { Text("URL o términos de búsqueda") },
                placeholder = { Text("github.com  ·  o  ·  noticias hoy") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val t = urlText.trim()
                        if (t.isNotBlank()) {
                            exec.run("Abrir") { it.browserOpen(t) }
                            urlText = ""
                            scope.launch { kotlinx.coroutines.delay(800); refreshTabs() }
                        }
                    },
                    enabled = !exec.busy && api != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PandaGreen.copy(alpha = 0.3f),
                        contentColor = PandaGreen,
                    ),
                ) { Text("Abrir en Brave") }
                Button(
                    onClick = { searchWeb() },
                    enabled = !webBusy && api != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PandaYellow.copy(alpha = 0.3f),
                        contentColor = PandaYellow,
                    ),
                ) { Text(if (webBusy) "…" else "Buscar links") }
            }
            Text(
                "«Abrir en Brave» lanza la página en el PC · «Buscar links» trae los " +
                    "resultados acá para elegir uno.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            webNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = PandaYellow)
            }
            webResults.forEach { result ->
                Spacer(Modifier.height(8.dp))
                WebResultRow(result, enabled = !exec.busy) {
                    exec.run("Abrir ${result.title.take(28)}") { it.browserOpen(result.url) }
                    scope.launch { kotlinx.coroutines.delay(800); refreshTabs() }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ─── YouTube ─────────────────────────────────────────────────────
        PandaCard(title = "YOUTUBE", accent = PandaMagenta) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ytQuery,
                    onValueChange = { ytQuery = it },
                    label = { Text("Buscar videos") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.height(0.dp))
                Button(
                    onClick = { searchYoutube() },
                    enabled = !ytBusy && api != null,
                    modifier = Modifier.padding(start = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PandaMagenta.copy(alpha = 0.3f),
                        contentColor = PandaMagenta,
                    ),
                ) { Text(if (ytBusy) "…" else "Buscar") }
            }
            ytNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = PandaYellow)
            }
            ytResults.forEach { v ->
                Spacer(Modifier.height(8.dp))
                YtVideoRow(v, enabled = !exec.busy) {
                    exec.run("▶ ${v.title.take(30)}") { it.youtubePlay(v.id) }
                    scope.launch { kotlinx.coroutines.delay(900); refreshTabs() }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ─── Pestañas abiertas ───────────────────────────────────────────
        PandaCard(title = "PESTAÑAS", accent = PandaCyan) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (cdpAvailable) "${tabs.size} abiertas" else "Brave sin depuración",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedButton(
                    onClick = { refreshTabs() },
                    enabled = !tabsBusy && api != null,
                ) { Text(if (tabsBusy) "…" else "↻") }
            }
            if (!cdpAvailable) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Brave no está corriendo con el puerto de depuración. Verificá " +
                        "~/.config/brave-flags.conf y reiniciá Brave para controlarlo " +
                        "desde acá.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PandaYellow,
                )
            }
            tabs.forEach { tab ->
                Spacer(Modifier.height(8.dp))
                TabRow(
                    tab = tab,
                    selected = tab.id == selectedTabId,
                    enabled = !exec.busy,
                    onActivate = {
                        selectedTabId = tab.id
                        exec.run("Foco pestaña") { it.browserActivate(tab.id) }
                    },
                    onBack = { exec.run("Atrás") { it.browserBack(tab.id) } },
                    onForward = { exec.run("Adelante") { it.browserForward(tab.id) } },
                    onReload = { exec.run("Recargar") { it.browserReload(tab.id) } },
                    onClose = {
                        exec.run("Cerrar pestaña") { it.browserClose(tab.id) }
                        scope.launch { kotlinx.coroutines.delay(600); refreshTabs() }
                    },
                )
            }
        }

        // ─── Control de la página activa ─────────────────────────────────
        if (cdpAvailable && tabs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val target = selectedTabId
            val targetTab = tabs.firstOrNull { it.id == target }
            PandaCard(title = "CONTROL DE PÁGINA", accent = PandaGreen) {
                Spacer(Modifier.height(4.dp))
                Text(
                    targetTab?.title?.ifBlank { "(sin título)" }
                        ?: "Tocá una pestaña para controlarla",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))

                // Scroll
                Text("Desplazar", style = MaterialTheme.typography.labelMedium, color = PandaGreen)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fun scroll(dir: String, label: String) {
                        target?.let { t -> exec.run("Scroll $label") { it.browserScroll(t, dir) } }
                    }
                    TabBtn("⤒", target != null && !exec.busy) { scroll("top", "arriba") }
                    TabBtn("▲", target != null && !exec.busy) { scroll("up", "↑") }
                    TabBtn("▼", target != null && !exec.busy) { scroll("down", "↓") }
                    TabBtn("⤓", target != null && !exec.busy) { scroll("bottom", "abajo") }
                }

                Spacer(Modifier.height(12.dp))

                // Clic por texto
                Text("Clic en link/botón por texto", style = MaterialTheme.typography.labelMedium, color = PandaGreen)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = clickText,
                        onValueChange = { clickText = it },
                        placeholder = { Text("texto del enlace") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val t = target; val q = clickText.trim()
                            if (t != null && q.isNotBlank()) {
                                exec.run("Clic «${q.take(20)}»") { it.browserClick(t, q) }
                            }
                        },
                        enabled = target != null && !exec.busy,
                        modifier = Modifier.padding(start = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PandaGreen.copy(alpha = 0.3f),
                            contentColor = PandaGreen,
                        ),
                    ) { Text("Clic") }
                }

                Spacer(Modifier.height(12.dp))

                // Escribir
                Text("Escribir en el campo de texto de la página", style = MaterialTheme.typography.labelMedium, color = PandaGreen)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = typeText,
                    onValueChange = { typeText = it },
                    placeholder = { Text("texto a escribir") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = typeSubmit, onCheckedChange = { typeSubmit = it })
                        Text("Enviar (Enter)", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            val t = target; val txt = typeText
                            if (t != null && txt.isNotBlank()) {
                                exec.run("Escribir") { it.browserType(t, txt, typeSubmit) }
                                typeText = ""
                            }
                        },
                        enabled = target != null && !exec.busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PandaGreen.copy(alpha = 0.3f),
                            contentColor = PandaGreen,
                        ),
                    ) { Text("Escribir") }
                }

                Spacer(Modifier.height(12.dp))

                // Elementos clicables — para pósters/banners sin texto (Netflix, etc.)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Elementos de la página",
                        style = MaterialTheme.typography.labelMedium,
                        color = PandaGreen,
                    )
                    OutlinedButton(
                        onClick = { loadLinks() },
                        enabled = target != null && !linksBusy,
                    ) { Text(if (linksBusy) "…" else "Listar") }
                }
                if (pageLinks.isEmpty()) {
                    Text(
                        "Lista los enlaces y botones clicables, incluso pósters o banners " +
                            "sin texto (Netflix, etc.). Tocá uno para hacer clic.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                pageLinks.forEach { link ->
                    Spacer(Modifier.height(6.dp))
                    PageLinkRow(link, enabled = !exec.busy) {
                        val t = target
                        if (t != null) {
                            exec.run("Clic «${link.label.take(24)}»") {
                                it.browserClickIndex(t, link.idx)
                            }
                            scope.launch {
                                kotlinx.coroutines.delay(1000)
                                refreshTabs(); loadLinks()
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PageLinkRow(link: PageLink, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, PandaGreen.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("›", color = PandaGreen, style = MaterialTheme.typography.titleMedium)
        Text(
            link.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        )
    }
}

@Composable
private fun WebResultRow(result: WebResult, enabled: Boolean, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, PandaYellow.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onOpen() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            result.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            result.url,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = PandaYellow,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (result.description.isNotBlank()) {
            Text(
                result.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun YtVideoRow(v: YtVideo, enabled: Boolean, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, PandaMagenta.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onPlay() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▶", color = PandaMagenta, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(0.dp))
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                v.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    v.channel.ifBlank { null },
                    v.duration.ifBlank { null },
                    v.views.ifBlank { null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TabRow(
    tab: BrowserTab,
    selected: Boolean,
    enabled: Boolean,
    onActivate: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                if (selected) 2.dp else 1.dp,
                PandaCyan.copy(alpha = if (selected) 0.9f else 0.4f),
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onActivate() },
        ) {
            Text(
                tab.title.ifBlank { "(sin título)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                tab.url,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabBtn("◀", enabled, onBack)
            TabBtn("▶", enabled, onForward)
            TabBtn("↻", enabled, onReload)
            TabBtn("foco", enabled, onActivate)
            TabBtn("✕", enabled, onClose)
        }
    }
}

@Composable
private fun TabBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp, vertical = 4.dp,
        ),
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}
