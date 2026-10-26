package io.github.pandaakira.apppanda.ui.more

import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import io.github.pandaakira.apppanda.data.PandaApi
import io.github.pandaakira.apppanda.data.models.CaffeineResponse
import io.github.pandaakira.apppanda.ui.components.ActionExecutor
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.PollingEffect
import kotlinx.coroutines.launch

// ─── Caffeine (Pantallas y Rápido) ───────────────────────────────────────────

private val CAFFEINE_PRESETS = listOf(30, 60, 120, 240)

private fun caffeineLabel(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60} h"

private fun remainingLabel(seconds: Long): String {
    val min = (seconds + 59) / 60
    return if (min < 60) "$min min" else "${min / 60} h ${"%02d".format(min % 60)} min"
}

/**
 * Tarjeta de caffeine autónoma: sondea /api/v1/caffeine (solo con la app
 * visible) y aplica los cambios con el [exec] de la pantalla que la contiene,
 * así su resultado sale en el mismo banner.
 */
@Composable
internal fun CaffeineControl(api: PandaApi?, exec: ActionExecutor) {
    var caffeine by remember { mutableStateOf<CaffeineResponse?>(null) }
    var fetchedAt by remember { mutableStateOf(0L) }
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    PollingEffect(
        api = api, key = refresh, intervalMs = 10_000,
        onResult = { caffeine = it; fetchedAt = android.os.SystemClock.elapsedRealtime() },
        onError = {},
    ) { it.caffeine() }

    val c = caffeine ?: return
    CaffeineCard(c, fetchedAt = fetchedAt, busy = exec.busy) { on, minutes ->
        val what = when {
            !on -> "Caffeine OFF"
            minutes == null -> "Caffeine ON"
            else -> "Caffeine ${caffeineLabel(minutes)}"
        }
        exec.run(what) { it.setCaffeine(on, minutes) }
        scope.launch { kotlinx.coroutines.delay(700); refresh++ }
    }
}

/** Interruptor + duraciones. Encendido sin duración = hasta apagarlo. */
@Composable
private fun CaffeineCard(
    c: CaffeineResponse,
    fetchedAt: Long,
    busy: Boolean,
    onSet: (on: Boolean, minutes: Int?) -> Unit,
) {
    val colors = LocalPandaColors.current
    // Cuenta regresiva local entre polls (el backend manda remaining_s).
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(fetchedAt, c.remainingS) {
        while (c.remainingS != null) {
            now = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(15_000)
        }
    }
    val remaining = c.remainingS?.let { (it - (now - fetchedAt) / 1000).coerceAtLeast(0) }
    val accent = if (c.active) colors.green else MaterialTheme.colorScheme.onSurfaceVariant

    PandaCard(title = "CAFFEINE", accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (c.active) "Activo" else "Inactivo",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        !c.active -> "el PC puede bloquearse por inactividad"
                        remaining != null -> "se apaga en ${remainingLabel(remaining)}"
                        else -> "sin límite de tiempo"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = c.active,
                onCheckedChange = { onSet(it, null) },
                enabled = !busy,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "mantener despierto por",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CAFFEINE_PRESETS.forEach { m ->
                FilterChip(
                    selected = false,
                    onClick = { onSet(true, m) },
                    enabled = !busy,
                    label = { Text(caffeineLabel(m)) },
                )
            }
        }
    }
}
