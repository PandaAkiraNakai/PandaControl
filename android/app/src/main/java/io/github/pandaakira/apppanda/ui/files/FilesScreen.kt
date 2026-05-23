package io.github.pandaakira.apppanda.ui.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import io.github.pandaakira.apppanda.ui.theme.PandaCyan
import io.github.pandaakira.apppanda.ui.theme.PandaGreen
import io.github.pandaakira.apppanda.ui.theme.PandaYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream

private enum class Tab { Recibir, Enviar }

@Composable
fun FilesScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(Tab.Recibir) }
    var index by remember { mutableStateOf<FilesIndexResponse?>(null) }
    var indexError by remember { mutableStateOf<String?>(null) }
    var banner by remember { mutableStateOf<String?>(null) }
    var bannerColor by remember { mutableStateOf(PandaGreen) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(api) {
        val current = api ?: return@LaunchedEffect
        try {
            index = withContext(Dispatchers.IO) { current.filesIndex() }
            indexError = null
        } catch (e: Exception) {
            indexError = e.message ?: e::class.simpleName
        }
    }

    fun setBanner(text: String, color: Color = PandaGreen) {
        banner = text
        bannerColor = color
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenHeader(
            "ARCHIVOS",
            (index?.uploadTo?.takeIf { it.isNotBlank() } ?: "")
                .let { if (it.isNotBlank()) "Upload → $it" else "transferencia bidireccional" },
        )
        Spacer(Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Tab.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = tab == t,
                    onClick = { tab = t },
                    shape = SegmentedButtonDefaults.itemShape(i, Tab.entries.size),
                ) { Text(t.name) }
            }
        }

        Spacer(Modifier.height(12.dp))

        indexError?.let {
            ErrorCard(it)
            Spacer(Modifier.height(8.dp))
        }

        banner?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, bannerColor, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    it,
                    color = bannerColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        when (tab) {
            Tab.Recibir -> RecibirTab(
                app = app,
                index = index,
                working = working,
                onWorking = { working = it },
                onBanner = ::setBanner,
            )
            Tab.Enviar -> EnviarTab(
                app = app,
                index = index,
                working = working,
                onWorking = { working = it },
                onBanner = ::setBanner,
            )
        }
    }
}

@Composable
private fun RecibirTab(
    app: PandaApp,
    index: FilesIndexResponse?,
    working: Boolean,
    onWorking: (Boolean) -> Unit,
    onBanner: (String, Color) -> Unit,
) {
    val api by app.repository.api.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var dirIdx by remember { mutableIntStateOf(0) }
    var listing by remember { mutableStateOf<FilesListResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val errorColor = MaterialTheme.colorScheme.error

    LaunchedEffect(api, dirIdx, refresh, index?.dirs?.size) {
        val current = api ?: return@LaunchedEffect
        if (index == null || index.dirs.isEmpty()) return@LaunchedEffect
        try {
            listing = withContext(Dispatchers.IO) { current.filesList(dirIdx) }
            error = null
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName
        }
    }

    if (index?.dirs?.isNotEmpty() == true) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            index.dirs.forEach { d ->
                val selected = dirIdx == d.idx
                OutlinedButton(
                    onClick = { dirIdx = d.idx },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selected) PandaYellow
                            else MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Text(d.label.ifBlank { d.path.substringAfterLast('/') }) }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        error?.let { item { ErrorCard(it) } }
        listing?.let { l ->
            if (l.files.isEmpty()) {
                item {
                    Text(
                        "(vacío)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(l.files) { file ->
                    FileRow(
                        file = file,
                        enabled = !working,
                        onTap = {
                            val client = api ?: return@FileRow
                            onWorking(true)
                            scope.launch {
                                val ok = downloadToPhone(
                                    context, client, l.dirIdx, file.name,
                                )
                                onWorking(false)
                                if (ok == null) {
                                    onBanner(
                                        "✓ ${file.name} guardado en Downloads",
                                        PandaGreen,
                                    )
                                } else {
                                    onBanner("✗ error: $ok", errorColor)
                                }
                                refresh++
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EnviarTab(
    app: PandaApp,
    index: FilesIndexResponse?,
    working: Boolean,
    onWorking: (Boolean) -> Unit,
    onBanner: (String, Color) -> Unit,
) {
    val api by app.repository.api.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val errorColor = MaterialTheme.colorScheme.error

    // OpenDocument (ACTION_OPEN_DOCUMENT) va directo a DocumentsUI/SAF y NO
    // requiere permisos de runtime: devuelve un content:// Uri legible.
    // Usamos OpenDocument en vez de GetContent a propósito: en varias ROMs
    // (HONOR/EMUI) el Photo Picker intercepta ACTION_GET_CONTENT y, si el mime
    // no es foto/vídeo, intenta reenviar a DocumentsUI con un intent que falla
    // por SecurityException → el proceso del picker crashea y parece que la app
    // se cerró. ACTION_OPEN_DOCUMENT esquiva el Photo Picker por completo.
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val client = api ?: return@rememberLauncherForActivityResult
        onWorking(true)
        scope.launch {
            val res = try {
                uploadFromPhone(context, client, uri, index?.maxUploadMb ?: 500)
            } catch (e: Exception) {
                false to ("error: " + (e.message?.take(80) ?: e::class.simpleName))
            }
            onWorking(false)
            if (res.first) {
                onBanner("✓ ${res.second}", PandaGreen)
            } else {
                onBanner("✗ ${res.second}", errorColor)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PandaCard(title = "DESTINO", accent = PandaCyan) {
            Text(
                index?.uploadTo?.ifBlank { "(sin shared_dir)" } ?: "—",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Máximo: ${index?.maxUploadMb ?: "?"} MB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = {
                try {
                    pickLauncher.launch(arrayOf("*/*"))
                } catch (e: Exception) {
                    onBanner(
                        "selector: ${e.message?.take(80) ?: e::class.simpleName}",
                        errorColor,
                    )
                }
            },
            enabled = !working && api != null,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PandaGreen.copy(alpha = 0.3f),
                contentColor = PandaGreen,
            ),
        ) {
            Text(if (working) "Subiendo…" else "Elegir archivo del celular")
        }
    }
}

@Composable
private fun FileRow(
    file: FileEntry,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, PandaCyan.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onTap() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${humanSize(file.size)} · ${humanMtime(file.mtime)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("⬇", color = PandaCyan, style = MaterialTheme.typography.titleMedium)
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
    name: String,
): String? = withContext(Dispatchers.IO) {
    try {
        val response = api.filesDownload(dirIdx, name)
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

/** Lee un Uri del ContentResolver y lo sube. Devuelve (ok, mensaje). */
private suspend fun uploadFromPhone(
    context: Context,
    api: PandaApi,
    uri: Uri,
    maxMb: Int,
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
        val res = api.filesUpload(displayName, bytes)
        if (res.result == "ok") {
            true to "subido como ${res.savedAs ?: displayName}"
        } else {
            false to (res.error ?: "error desconocido")
        }
    } catch (e: Exception) {
        false to (e.message ?: e::class.simpleName ?: "error")
    }
}
