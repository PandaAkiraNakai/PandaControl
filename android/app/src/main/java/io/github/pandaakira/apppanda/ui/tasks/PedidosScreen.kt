package io.github.pandaakira.apppanda.ui.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.PedidoRow
import io.github.pandaakira.apppanda.data.models.PedidosResponse
import io.github.pandaakira.apppanda.ui.components.EmptyState
import io.github.pandaakira.apppanda.ui.components.ErrorCard
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import io.github.pandaakira.apppanda.ui.theme.PandaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Color por prioridad — el acento de la tarjeta. */
@Composable
private fun prioridadColor(p: String): Color = when (p) {
    "urgente" -> LocalPandaColors.current.red
    "alta" -> LocalPandaColors.current.orange
    "normal" -> LocalPandaColors.current.cyan
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Color por estado — para el badge. */
@Composable
private fun estadoColor(e: String): Color = when (e) {
    "en_progreso" -> LocalPandaColors.current.yellow
    "pendiente" -> LocalPandaColors.current.cyan
    "hecho" -> LocalPandaColors.current.green
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun PedidosScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    var data by remember { mutableStateOf<PedidosResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var verHechos by remember { mutableStateOf(false) }

    LaunchedEffect(api, verHechos) {
        val current = api ?: return@LaunchedEffect
        try {
            data = withContext(Dispatchers.IO) { current.pedidos(verHechos) }
            error = null
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName
        }
    }

    val pedidos = data?.pedidos ?: emptyList()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            ScreenHeader(
                "PEDIDOS",
                data?.let { "${it.total} ${if (verHechos) "(todos)" else "activos"}" }
                    ?: "cargando…",
            )
        }

        item {
            FilterChip(
                selected = verHechos,
                onClick = { verHechos = !verHechos },
                label = { Text("incluir hechos y cancelados") },
            )
        }

        error?.let { item { ErrorCard(it) } }
        data?.error?.let { item { ErrorCard(it) } }

        if (data != null && pedidos.isEmpty()) {
            item {
                EmptyState(
                    if (verHechos) "No hay pedidos en la base."
                    else "No hay pedidos pendientes. 🎉",
                )
            }
        }

        items(pedidos, key = { it.id }) { p -> PedidoCard(p) }
    }
}

@Composable
private fun PedidoCard(p: PedidoRow) {
    var expanded by remember { mutableStateOf(false) }
    val accent = prioridadColor(p.prioridad)
    val tachado = p.estado == "hecho" || p.estado == "cancelado"

    PandaCard(
        title = "${p.prioridad.uppercase()} · ${p.estado.replace('_', ' ')}",
        accent = if (tachado) estadoColor(p.estado) else accent,
        modifier = Modifier.clickable { expanded = !expanded },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                p.titulo,
                style = MaterialTheme.typography.titleMedium,
                color = if (tachado) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (p.vence.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "vence ${p.vence}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalPandaColors.current.orange,
                )
            }
        }

        if (p.detalle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                p.detalle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                if (p.notas.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "notas: ${p.notas}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "creado ${p.creado} · act. ${p.actualizado}" +
                        if (p.completado.isNotBlank()) " · hecho ${p.completado}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
