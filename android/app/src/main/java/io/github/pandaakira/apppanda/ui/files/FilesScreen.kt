package io.github.pandaakira.apppanda.ui.files
import io.github.pandaakira.apppanda.ui.theme.LocalPandaShapes
import io.github.pandaakira.apppanda.ui.theme.BuiltInPalette
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.PandaApi
import io.github.pandaakira.apppanda.data.models.FileEntry
import io.github.pandaakira.apppanda.data.models.FilesIndexResponse
import io.github.pandaakira.apppanda.data.models.FilesListResponse
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var index by remember { mutableStateOf<FilesIndexResponse?>(null) }
    var indexError by remember { mutableStateOf<String?>(null) }

    // Navegación: qué shared_dir y qué subcarpeta (rel) dentro de él.
    var dirIdx by remember { mutableIntStateOf(0) }
    var rel by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf<FilesListResponse?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }

    var banner by remember { mutableStateOf<String?>(null) }
    var bannerColor by remember { mutableStateOf(BuiltInPalette.green) }
    val okColor = LocalPandaColors.current.green
    val errColor = MaterialTheme.colorScheme.error
    fun setBanner(text: String, color: Color = okColor) {
        banner = text; bannerColor = color
    }

    // Diálogos
    var actionTarget by remember { mutableStateOf<FileEntry?>(null) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val client = api ?: return@rememberLauncherForActivityResult
        working = true
        scope.launch {
            val res = try {
                uploadFromPhone(context, client, uri, index?.maxUploadMb ?: 500, dirIdx, rel)
            } catch (e: Exception) {
                false to ("error: " + (e.message?.take(80) ?: e::class.simpleName))
            }
            working = false
            if (res.first) setBanner("✓ ${res.second}", okColor)
            else setBanner("✗ ${res.second}", errColor)
            refresh++
        }
    }

    LaunchedEffect(api) {
        val current = api ?: return@LaunchedEffect
        try {
            index = withContext(Dispatchers.IO) { current.filesIndex() }
            indexError = null
        } catch (e: Exception) {
            indexError = e.message ?: e::class.simpleName
        }
    }

    LaunchedEffect(api, dirIdx, rel, refresh, index?.dirs?.size) {
        val current = api ?: return@LaunchedEffect
        if (index == null || index!!.dirs.isEmpty()) return@LaunchedEffect
        try {
            listing = withContext(Dispatchers.IO) { current.filesList(dirIdx, rel) }
            listError = listing?.error
        } catch (e: Exception) {
            listError = e.message ?: e::class.simpleName
        }
    }

    fun descend(name: String) { rel = if (rel.isEmpty()) name else "$rel/$name" }
    fun goTo(target: String) { rel = target }
    fun goUp() { rel = rel.substringBeforeLast('/', "") }

    fun fileAction(label: String, color: Color, block: suspend (PandaApi) -> io.github.pandaakira.apppanda.data.models.ActionResult) {
        val client = api ?: return
        working = true
        scope.launch {
            val r = try {
                withContext(Dispatchers.IO) { block(client) }
            } catch (e: Exception) {
                working = false
                setBanner("✗ $label · ${e.message?.take(80) ?: e::class.simpleName}", errColor)
                return@launch
            }
            working = false
            if (r.ok) setBanner("✓ $label", okColor)
            else setBanner("✗ $label · ${r.result.ifBlank { r.error.orEmpty() }}", errColor)
            refresh++
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenHeader("ARCHIVOS", listing?.path ?: "navegador de archivos del PC")
        Spacer(Modifier.height(8.dp))

        indexError?.let { ErrorCard(it); Spacer(Modifier.height(8.dp)) }

        // Selector de shared_dir (si hay varios)
        index?.dirs?.takeIf { it.isNotEmpty() }?.let { dirs ->
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                dirs.forEach { d ->
                    val selected = dirIdx == d.idx
                    OutlinedButton(
                        onClick = { dirIdx = d.idx; rel = "" },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (selected) LocalPandaColors.current.yellow
                                else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) { Text(d.label.ifBlank { d.path.substringAfterLast('/') }) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Breadcrumb
        Breadcrumb(rel = rel, onCrumb = ::goTo)
        Spacer(Modifier.height(8.dp))

        // Acciones de la carpeta actual
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    try { pickLauncher.launch(arrayOf("*/*")) }
                    catch (e: Exception) { setBanner("selector: ${e.message?.take(60)}", errColor) }
                },
                enabled = !working && api != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LocalPandaColors.current.green.copy(alpha = 0.25f),
                    contentColor = LocalPandaColors.current.green,
                ),
            ) { Text(if (working) "…" else "⬆ Subir aquí") }
            Button(
                onClick = { showNewFolder = true },
                enabled = !working && api != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LocalPandaColors.current.cyan.copy(alpha = 0.25f),
                    contentColor = LocalPandaColors.current.cyan,
                ),
            ) { Text("＋ Carpeta") }
        }

        Spacer(Modifier.height(8.dp))
        banner?.let {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(LocalPandaShapes.current.cornerSmall))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(LocalPandaShapes.current.border, bannerColor, RoundedCornerShape(LocalPandaShapes.current.cornerSmall))
                    .clickable { banner = null }
                    .padding(12.dp),
            ) { Text(it, color = bannerColor, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Fila "subir un nivel"
            if (rel.isNotEmpty()) {
                item {
                    UpRow(enabled = !working) { goUp() }
                }
            }
            listError?.let { item { ErrorCard(it) } }
            listing?.takeIf { it.error == null }?.let { l ->
                if (l.files.isEmpty()) {
                    item {
                        Text("(carpeta vacía)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp))
                    }
                } else {
                    items(l.files) { file ->
                        FileRow(
                            file = file,
                            enabled = !working,
                            onTap = {
                                if (file.isDir) descend(file.name) else actionTarget = file
                            },
                            onLongPress = { actionTarget = file },
                        )
                    }
                }
            }
        }
    }

    // ─── Diálogo de acciones por entrada ───────────────────────────────────
    actionTarget?.let { f ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(f.name, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    if (!f.isDir) {
                        DialogAction("⬇  Descargar al celular") {
                            actionTarget = null
                            val client = api ?: return@DialogAction
                            working = true
                            scope.launch {
                                val err = downloadToPhone(context, client, dirIdx, rel, f.name)
                                working = false
                                if (err == null) setBanner("✓ ${f.name} en Downloads", okColor)
                                else setBanner("✗ $err", errColor)
                            }
                        }
                    }
                    DialogAction("↗  Abrir en el PC") {
                        actionTarget = null
                        fileAction("Abrir ${f.name}", okColor) { it.filesOpen(dirIdx, rel, f.name) }
                    }
                    DialogAction("✎  Renombrar") {
                        actionTarget = null; renameTarget = f
                    }
                    DialogAction("🗑  Borrar", color = errColor) {
                        actionTarget = null; deleteTarget = f
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionTarget = null }) { Text("Cerrar") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    // ─── Nueva carpeta ──────────────────────────────────────────────────────
    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("Nueva carpeta") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, placeholder = { Text("nombre") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val n = name.trim(); showNewFolder = false
                        fileAction("Carpeta $n", okColor) { it.filesMkdir(dirIdx, rel, n) }
                    },
                ) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancelar") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    // ─── Renombrar ────────────────────────────────────────────────────────
    renameTarget?.let { f ->
        var newName by remember { mutableStateOf(f.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Renombrar") },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newName != f.name,
                    onClick = {
                        val nn = newName.trim(); renameTarget = null
                        fileAction("Renombrar → $nn", okColor) {
                            it.filesRename(dirIdx, rel, f.name, nn)
                        }
                    },
                ) { Text("Renombrar") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancelar") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    // ─── Confirmar borrado ──────────────────────────────────────────────────
    deleteTarget?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("¿Borrar ${if (f.isDir) "carpeta" else "archivo"}?") },
            text = {
                Text(
                    if (f.isDir) "Se borrará «${f.name}» y todo su contenido. No se puede deshacer."
                    else "Se borrará «${f.name}». No se puede deshacer.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    fileAction("Borrar ${f.name}", okColor) {
                        it.filesDelete(dirIdx, rel, f.name, recursive = f.isDir)
                    }
                }) { Text("Borrar", color = errColor) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

@Composable
private fun DialogAction(label: String, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, color = color, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Breadcrumb(rel: String, onCrumb: (String) -> Unit) {
    val crumbs = buildList {
        add("⌂" to "")
        if (rel.isNotEmpty()) {
            var acc = ""
            rel.split('/').forEach { part ->
                acc = if (acc.isEmpty()) part else "$acc/$part"
                add(part to acc)
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, (label, target) ->
            if (i > 0) Text(" / ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (i == crumbs.lastIndex) LocalPandaColors.current.yellow
                        else LocalPandaColors.current.cyan,
                modifier = Modifier
                    .clip(RoundedCornerShape(LocalPandaShapes.current.cornerSmall))
                    .clickable { onCrumb(target) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun UpRow(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(LocalPandaShapes.current.corner))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📁  ..", style = MaterialTheme.typography.bodyMedium,
            color = LocalPandaColors.current.cyan)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: FileEntry,
    enabled: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val accent = if (file.isDir) LocalPandaColors.current.yellow else LocalPandaColors.current.cyan
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LocalPandaShapes.current.corner))
            .background(MaterialTheme.colorScheme.surface)
            .border(LocalPandaShapes.current.border, accent.copy(alpha = 0.4f), RoundedCornerShape(LocalPandaShapes.current.corner))
            .combinedClickable(enabled = enabled, onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (file.isDir) "📁" else "📄", modifier = Modifier.padding(end = 10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (file.isDir) humanMtime(file.mtime)
                else "${humanSize(file.size)} · ${humanMtime(file.mtime)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(if (file.isDir) "›" else "⋮", color = accent, style = MaterialTheme.typography.titleMedium)
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var u = 0
    while (v >= 1024 && u < units.lastIndex) {
        v /= 1024
        u++
    }
    return "%.1f %s".format(v, units[u])
}

private fun humanMtime(epoch: Long): String {
    val ms = epoch * 1000
    val deltaS = (System.currentTimeMillis() - ms) / 1000
    return when {
        deltaS < 60 -> "ahora"
        deltaS < 3600 -> "${deltaS / 60} min"
        deltaS < 86400 -> "${deltaS / 3600} h"
        deltaS < 86400 * 7 -> "${deltaS / 86400} d"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    }
}

/** Descarga el archivo del backend y lo escribe en Downloads del celular vía
 *  MediaStore (Android 10+). Devuelve null si OK, o mensaje de error. */
private suspend fun downloadToPhone(
    context: Context,
    api: PandaApi,
    dirIdx: Int,
    rel: String,
    name: String,
): String? = withContext(Dispatchers.IO) {
    try {
        val response = api.filesDownload(dirIdx, rel, name)
        if (response.status.value !in 200..299) {
            return@withContext "HTTP ${response.status.value}"
        }
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Files.getContentUri("external")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values)
            ?: return@withContext "no pude crear archivo destino"
        try {
            resolver.openOutputStream(uri).use { os ->
                if (os == null) return@withContext "outputStream null"
                response.bodyAsChannel().toInputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        os.write(buf, 0, n)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
            }
            null
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            e.message ?: e::class.simpleName
        }
    } catch (e: Exception) {
        e.message ?: e::class.simpleName
    }
}

/** Lee un Uri del ContentResolver y lo sube a dirIdx/rel. Devuelve (ok, mensaje). */
private suspend fun uploadFromPhone(
    context: Context,
    api: PandaApi,
    uri: Uri,
    maxMb: Int,
    dirIdx: Int,
    rel: String,
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        var displayName = "archivo"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) displayName = c.getString(nameIdx) ?: displayName
                if (sizeIdx >= 0) size = c.getLong(sizeIdx)
            }
        }
        val maxBytes = maxMb.toLong() * 1024 * 1024
        if (size > maxBytes) {
            return@withContext false to "archivo > ${maxMb} MB"
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext false to "no pude leer el archivo"
        if (bytes.size.toLong() > maxBytes) {
            return@withContext false to "archivo > ${maxMb} MB"
        }
        val res = api.filesUpload(displayName, bytes, dirIdx, rel)
        if (res.result == "ok") {
            true to "subido como ${res.savedAs ?: displayName}"
        } else {
            false to (res.error ?: "error desconocido")
        }
    } catch (e: Exception) {
        false to (e.message ?: e::class.simpleName ?: "error")
    }
}
