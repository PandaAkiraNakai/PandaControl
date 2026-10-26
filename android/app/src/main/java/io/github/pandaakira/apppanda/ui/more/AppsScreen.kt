package io.github.pandaakira.apppanda.ui.more

import io.github.pandaakira.apppanda.ui.theme.LocalPandaShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader

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

    io.github.pandaakira.apppanda.ui.components.PollingEffect(
        api = api, intervalMs = 30_000,
        onResult = { apps = it.apps }, onError = { error = it },
    ) { it.apps() }

    val icons = rememberAppIcons(api, apps)

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
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                gridItems(apps!!, key = { it.name }) { a ->
                    AppTile(a, icon = icons[a.name], enabled = !exec.busy) {
                        exec.run("Abrir ${a.label}") { it.launchApp(a.name) }
                    }
                }
            }
        }
        io.github.pandaakira.apppanda.ui.components.ActionResultBanner(exec)
    }
}

/** Íconos reales de las apps (tema de íconos del PC), una sola descarga por app. */
@Composable
internal fun rememberAppIcons(
    api: io.github.pandaakira.apppanda.data.PandaApi?,
    apps: List<io.github.pandaakira.apppanda.data.models.AppEntry>?,
): Map<String, androidx.compose.ui.graphics.ImageBitmap> {
    val icons = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>() }
    LaunchedEffect(api, apps) {
        val current = api ?: return@LaunchedEffect
        apps.orEmpty().filter { it.icon && it.name !in icons }.forEach { a ->
            runCatching { current.appIcon(a.name) }.getOrNull()
                ?.let { b -> android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) }
                ?.let { icons[a.name] = it.asImageBitmap() }
        }
    }
    return icons
}

@Composable
internal fun AppTile(
    a: io.github.pandaakira.apppanda.data.models.AppEntry,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Sin ícono del PC se usa el emoji inicial del label (o ▶). Extraer el
    // primer code point completo (emojis son surrogate pairs en UTF-16 y
    // .first() devuelve solo el high surrogate, rompiendo el render).
    // label puede venir vacío si el config.toml tiene un label="" mal
    // tipeado — codePointAt(0) sobre "" tira StringIndexOutOfBoundsException
    // y tumbaba toda la pantalla.
    val (glyph, rest) = if (a.label.isEmpty()) {
        "▶" to a.name
    } else {
        val firstCp = a.label.codePointAt(0)
        val firstCpStr = String(Character.toChars(firstCp))
        val isEmoji = firstCp >= 0x2300 || firstCp == 0x25B6
        if (isEmoji) {
            firstCpStr to a.label.substring(firstCpStr.length).trim()
        } else {
            "▶" to a.label
        }
    }
    val shape = RoundedCornerShape(LocalPandaShapes.current.corner)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(LocalPandaShapes.current.border,
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f), shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
        } else {
            // fontFamily explícitamente Default (null) para que el sistema
            // resuelva el fallback a Noto Color Emoji para los glyphs no-ASCII.
            Text(
                glyph,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
                ),
            )
        }
        Text(
            rest.ifBlank { a.name },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
