package io.github.pandaakira.apppanda.ui.themes

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.ThemeColors
import io.github.pandaakira.apppanda.data.models.ThemeDef
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.theme.BuiltInPalette
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import io.github.pandaakira.apppanda.ui.theme.PandaIcons
import io.github.pandaakira.apppanda.ui.theme.parseHexColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val themeJson = Json { ignoreUnknownKeys = true }

/** Colores que representan al tema incluido, como ThemeColors, para el preview
 *  y para que "Incluido" se muestre con su swatch igual que los demás. */
private val builtInColors = ThemeColors(
    background = "#0D0D11", surface = "#15151C", surfaceHigh = "#1E1E26",
    onSurface = "#E0E0E6", onSurfaceMuted = "#8A8A99",
    yellow = "#FFEA00", magenta = "#FF007A", cyan = "#00E5FF",
    green = "#00FF7F", red = "#FF3860", orange = "#FFA500",
)

@Composable
fun ThemesScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    val selected by app.settings.selectedTheme.collectAsState(initial = null)
    var themes by remember { mutableStateOf<List<ThemeDef>?>(null) }
    var dir by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val accent = LocalPandaColors.current.cyan

    LaunchedEffect(api) {
        val current = api ?: return@LaunchedEffect
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { current.themes() }
                themes = resp.themes
                dir = resp.dir
                error = null
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            }
        }
    }

    val selectedName = selected?.name.orEmpty()
    val builtInActive = selected?.isCustom != true
    // Fondo actualmente aplicado (para marcar la miniatura activa).
    val activeBg = remember(selected?.specJson) {
        val j = selected?.specJson
        if (j.isNullOrBlank()) "" else runCatching {
            themeJson.decodeFromString(ThemeDef.serializer(), j).backgroundImage
        }.getOrDefault("")
    }

    fun apply(theme: ThemeDef) {
        scope.launch {
            app.settings.saveTheme(
                theme.name, themeJson.encodeToString(ThemeDef.serializer(), theme),
            )
        }
    }

    // El tema incluido (Cyberpunk) — siempre disponible, incluso sin backend.
    // Se reusa en la sección "Cyberpunk" y en los estados sin datos.
    val builtIn: @Composable () -> Unit = {
        ThemeCard(
            app = app,
            name = "Cyberpunk (incluido)",
            colors = builtInColors,
            meta = "default · outlined · r12 · b1",
            cornerDp = 12,
            borderDp = 1,
            wallpapers = emptyList(),
            activeWallpaper = "",
            selected = builtInActive,
            accent = accent,
            onClick = { scope.launch { app.settings.clearTheme() } },
            onPickWallpaper = {},
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            ScreenHeader(
                "TEMAS",
                "carpetas = subcarpetas del PC · tap para aplicar",
            )
        }

        val loaded = themes
        if (loaded.isNullOrEmpty() || api == null || error != null) {
            // Sin datos utilizables del backend: muestra solo el incluido.
            item(key = "hdr-Cyberpunk") { CategoryLabel("Cyberpunk") }
            item(key = "builtin") { builtIn() }
            when {
                api == null -> item {
                    EmptyState("Configura el backend en Ajustes para ver los temas de la carpeta.")
                }
                error != null -> item { ErrorCard(error!!) }
                loaded == null -> item { EmptyState("Cargando temas…") }
                else -> item {
                    EmptyState(
                        "Sin temas en la carpeta del PC. Deja un *.json (o una " +
                            "subcarpeta con temas) en:\n$dir\ny vuelve a entrar.",
                    )
                }
            }
        } else {
            // Agrupa por categoría (subcarpeta). El tema incluido va dentro de
            // "Cyberpunk", que siempre se muestra aunque no haya temas ahí.
            val grouped = loaded.groupBy { it.category.ifBlank { OTHER_CATEGORY } }
            orderCategories(grouped.keys + "Cyberpunk").forEach { cat ->
                item(key = "hdr-$cat") { CategoryLabel(cat) }
                if (cat == "Cyberpunk") item(key = "builtin") { builtIn() }
                items(grouped[cat] ?: emptyList(), key = { it.id }) { theme ->
                    val isSel = !builtInActive && theme.name == selectedName
                    ThemeCard(
                        app = app,
                        name = theme.name,
                        colors = theme.colors,
                        meta = "${theme.font} · ${theme.iconStyle} · r${theme.corner} · b${theme.border}",
                        cornerDp = theme.corner,
                        borderDp = theme.border,
                        wallpapers = theme.backgroundImages,
                        activeWallpaper = if (isSel) activeBg else "",
                        selected = isSel,
                        accent = accent,
                        // Tap a la tarjeta: aplica con el primer fondo (el default).
                        onClick = { apply(theme) },
                        // Tap a una miniatura: aplica con ese fondo específico.
                        onPickWallpaper = { wp -> apply(theme.copy(backgroundImage = wp)) },
                    )
                }
            }
        }
    }
}

/** Categoría para temas sueltos en la raíz (sin subcarpeta). */
private const val OTHER_CATEGORY = "Otros"

/** Orden fijo de las categorías conocidas; el resto va alfabético. */
private val CATEGORY_ORDER = listOf("Cyberpunk", "Retro", "Oficina")

/** Ordena las categorías: primero las conocidas en su orden, luego el resto
 *  alfabético, y "Otros" (sin carpeta) al final. */
private fun orderCategories(cats: Collection<String>): List<String> {
    val set = cats.toSet()
    val known = CATEGORY_ORDER.filter { it in set }
    val rest = (set - CATEGORY_ORDER.toSet() - OTHER_CATEGORY).sorted()
    val tail = if (OTHER_CATEGORY in set) listOf(OTHER_CATEGORY) else emptyList()
    return known + rest + tail
}

/** Encabezado de sección para una categoría de temas. */
@Composable
private fun CategoryLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LocalPandaColors.current.cyan,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun ThemeCard(
    app: PandaApp,
    name: String,
    colors: ThemeColors,
    meta: String,
    cornerDp: Int,
    borderDp: Int,
    wallpapers: List<String>,
    activeWallpaper: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onPickWallpaper: (String) -> Unit,
) {
    // La tarjeta se previsualiza con la forma y el borde del PROPIO tema, así
    // el cambio de esquinas/bordes se ve directo en la lista.
    val shape = RoundedCornerShape(cornerDp.coerceIn(0, 48).dp)
    val borderColor = if (selected) accent else accent.copy(alpha = 0.35f)
    val borderW = (if (selected) borderDp + 1 else borderDp).coerceIn(0, 9).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(borderW, borderColor, shape)
            .clickable { onClick() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    PandaIcons.checkCircle,
                    contentDescription = "Aplicado",
                    tint = accent,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        SwatchRow(colors)
        // Vista previa / selector de fondo: se muestra siempre que el tema
        // traiga al menos una imagen (con varias, funciona como selector).
        if (wallpapers.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "FONDO",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                wallpapers.forEach { wp ->
                    WallpaperThumb(
                        app = app,
                        name = wp,
                        selected = selected && wp == activeWallpaper,
                        accent = accent,
                        onClick = { onPickWallpaper(wp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WallpaperThumb(
    app: PandaApp,
    name: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val api by app.repository.api.collectAsState()
    var bmp by remember(name, api) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(name, api) {
        val current = api ?: return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val b = current.themeImage(name)
                BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(width = 54.dp, height = 96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) accent else accent.copy(alpha = 0.3f),
                RoundedCornerShape(8.dp),
            )
            .clickable { onClick() },
    ) {
        bmp?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun SwatchRow(colors: ThemeColors) {
    val swatches = listOf(
        colors.background, colors.surface, colors.yellow, colors.magenta,
        colors.cyan, colors.green, colors.orange, colors.red,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        swatches.forEach { hex ->
            val c = parseHexColor(hex) ?: BuiltInPalette.surfaceHigh
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
            )
        }
    }
}
