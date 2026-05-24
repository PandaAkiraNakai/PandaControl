package io.github.pandaakira.apppanda.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.PandaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Scaffold de pantalla read-only que: 1) muestra el header, 2) fetchea
 * datos del API cuando el cliente está disponible, 3) renderiza loading /
 * error / contenido. Las pantallas concretas solo proveen `fetch` y
 * `content`.
 */
@Composable
fun <T : Any> RemoteScreen(
    app: PandaApp,
    title: String,
    subtitle: String? = null,
    refreshKey: Any? = Unit,
    fetch: suspend (PandaApi) -> T,
    content: LazyListScope.(T) -> Unit,
) {
    val api by app.repository.api.collectAsState()
    var data by remember(refreshKey) { mutableStateOf<T?>(null) }
    var error by remember(refreshKey) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api, refreshKey) {
        val current = api ?: return@LaunchedEffect
        scope.launch {
            try {
                data = withContext(Dispatchers.IO) { fetch(current) }
                error = null
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier,
    ) {
        item { ScreenHeader(title, subtitle) }
        when {
            api == null -> item { EmptyState("Configura el backend en Ajustes.") }
            error != null -> item { ErrorCard(error!!) }
            data != null -> content(data!!)
            else -> item { EmptyState("Cargando…") }
        }
    }
}
