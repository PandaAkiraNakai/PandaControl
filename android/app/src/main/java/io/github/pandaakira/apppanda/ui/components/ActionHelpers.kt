package io.github.pandaakira.apppanda.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import io.github.pandaakira.apppanda.data.PandaApi
import io.github.pandaakira.apppanda.data.models.ActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fetchea [fetch] apenas hay [api] disponible y cada [intervalMs] mientras
 * el composable siga en pantalla, reintentando solo ante un error
 * transitorio (red, Doze) en vez de quedar pegado al primer fallo — que es
 * el bug que tenían todas las pantallas antes de este helper: un solo
 * intento en LaunchedEffect(api) que, si fallaba una vez, no se volvía a
 * disparar hasta que la pantalla se recreaba (cambiar de tab y volver).
 * [key] fuerza un refetch inmediato (cambiar de filtro, un botón
 * "Refrescar", etc.) sin esperar al próximo tick.
 */
@Composable
fun <T> PollingEffect(
    api: PandaApi?,
    key: Any? = Unit,
    intervalMs: Long = 10_000,
    onResult: (T) -> Unit,
    onError: (String?) -> Unit,
    fetch: suspend (PandaApi) -> T,
) {
    LaunchedEffect(api, key) {
        val current = api ?: return@LaunchedEffect
        while (true) {
            try {
                onResult(withContext(Dispatchers.IO) { fetch(current) })
                onError(null)
            } catch (e: Exception) {
                onError(e.message ?: e::class.simpleName)
            }
            delay(intervalMs)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Sí",
    cancelLabel: String = "Cancelar",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = MaterialTheme.colorScheme.primary) },
        text = { Text(message, color = MaterialTheme.colorScheme.onSurface) },
        confirmButton = {
            TextButton(onClick = { onConfirm() }) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/**
 * Estado simple para mostrar el resultado de la última acción ejecutada.
 * Se renderiza como banner pegado al final con `ActionResultBanner`.
 */
class ActionExecutor(
    private val scope: CoroutineScope,
    private val getApi: () -> PandaApi?,
) {
    var status by mutableStateOf<String?>(null)
        private set
    var ok by mutableStateOf<Boolean?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    fun run(label: String, block: suspend (PandaApi) -> ActionResult) {
        val api = getApi() ?: run {
            status = "Sin backend"; ok = false; return
        }
        busy = true
        status = "$label…"
        ok = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { block(api) }
                ok = r.ok
                status = if (r.ok) "$label · ok"
                         else "$label · ${r.result.ifBlank { r.error.orEmpty() }}".take(140)
            } catch (e: Exception) {
                ok = false
                status = "$label · ${e.message?.take(120) ?: e::class.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun clear() {
        status = null; ok = null
    }
}

@Composable
fun rememberActionExecutor(getApi: () -> PandaApi?): ActionExecutor {
    val scope = rememberCoroutineScope()
    return remember { ActionExecutor(scope, getApi) }
}

@Composable
fun ActionResultBanner(executor: ActionExecutor) {
    val msg = executor.status ?: return
    val color = when (executor.ok) {
        true -> MaterialTheme.colorScheme.tertiary
        false -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    PandaCard(
        title = if (executor.ok == true) "OK"
                else if (executor.ok == false) "ERROR"
                else "WAIT",
        accent = color,
    ) {
        Text(msg, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
