package io.github.pandaakira.apppanda.ui.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.MemoriaRow
import io.github.pandaakira.apppanda.data.models.MemoriasResponse
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import io.github.pandaakira.apppanda.ui.theme.PandaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tipos conocidos de la base `mem`, en el orden que se muestran los chips. */
private val TIPOS = listOf("user", "feedback", "project", "reference", "nota")

/** Color de acento por tipo de memoria — consistente en chip y tarjeta. */
@Composable
private fun tipoColor(tipo: String): Color = when (tipo) {
    "user" -> LocalPandaColors.current.cyan
    "feedback" -> LocalPandaColors.current.magenta
    "project" -> LocalPandaColors.current.green
    "reference" -> LocalPandaColors.current.yellow
    "nota" -> LocalPandaColors.current.orange
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun MemoriasScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var data by remember { mutableStateOf<MemoriasResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    var query by remember { mutableStateOf("") }
    var tipoFiltro by remember { mutableStateOf<String?>(null) }
    var verArchivadas by remember { mutableStateOf(false) }

    LaunchedEffect(api, refresh, verArchivadas) {
        val current = api ?: return@LaunchedEffect
        try {
            data = withContext(Dispatchers.IO) { current.memorias(verArchivadas) }
            error = null
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName
        }
    }

    val todas = data?.memorias ?: emptyList()
    val filtradas = remember(todas, query, tipoFiltro) {
        val q = query.trim().lowercase()
        todas.filter { m ->
            (tipoFiltro == null || m.tipo == tipoFiltro) &&
                (q.isEmpty() ||
                    m.nombre.lowercase().contains(q) ||
                    m.resumen.lowercase().contains(q) ||
                    m.contenido.lowercase().contains(q) ||
                    m.tags.lowercase().contains(q))
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            ScreenHeader(
                "MEMORIAS",
                data?.let { "${filtradas.size}/${it.total} · solo lectura" }
                    ?: "cargando…",
            )
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Buscar por nombre, texto o tag…") },
                leadingIcon = {
                    Icon(PandaIcons.psychology, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(PandaIcons.close, contentDescription = "Limpiar")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(
                    selected = tipoFiltro == null,
                    onClick = { tipoFiltro = null },
                    label = { Text("todas") },
                )
                TIPOS.forEach { t ->
                    FilterChip(
                        selected = tipoFiltro == t,
                        onClick = { tipoFiltro = if (tipoFiltro == t) null else t },
                        label = { Text(t) },
                    )
                }
                FilterChip(
                    selected = verArchivadas,
                    onClick = { verArchivadas = !verArchivadas },
                    label = { Text("archivadas") },
                )
            }
        }

        error?.let { item { ErrorCard(it) } }
        data?.error?.let { item { ErrorCard(it) } }

        if (data != null && filtradas.isEmpty()) {
            item {
                EmptyState(
                    if (todas.isEmpty()) "No hay memorias guardadas."
                    else "Ninguna memoria coincide con el filtro.",
                )
            }
        }

        items(filtradas, key = { it.id }) { mem ->
            MemoriaCard(mem)
        }
    }
}

@Composable
private fun MemoriaCard(mem: MemoriaRow) {
    var expanded by remember { mutableStateOf(false) }
    val accent = tipoColor(mem.tipo)

    PandaCard(
        title = mem.tipo.uppercase(),
        accent = accent,
        modifier = Modifier.clickable { expanded = !expanded },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                mem.nombre,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (mem.activo == 0) {
                Text(
                    "archivada",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                if (expanded) PandaIcons.keyboardArrowUp else PandaIcons.keyboardArrowDown,
                contentDescription = null,
                tint = accent,
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            mem.resumen,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(10.dp))
                Text(
                    mem.contenido,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (mem.tags.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "tags: ${mem.tags}",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${mem.dispositivo} · act. ${mem.actualizado}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
