package io.github.pandaakira.apppanda.ui.components
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.PandaApi
import io.github.pandaakira.apppanda.data.Profile
import io.github.pandaakira.apppanda.data.WakeOnLan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val WAIT_BOOT_S = 150

/**
 * Botón Encender (Wake-on-LAN) del PC del perfil activo. Manda el magic packet
 * por todas las rutas a la vez:
 *  - directo desde el celular, si está en la LAN de casa;
 *  - por cada otro PC configurado que responda (relay desde su LAN), así
 *    funciona fuera de casa mientras haya otro equipo encendido en la casa.
 * Después espera a que el backend del PC conteste para confirmar el arranque.
 *
 * Si el perfil no tiene MAC no se muestra: se aprende sola la primera vez que
 * la app se conecta al PC, o se escribe en Ajustes → perfil.
 */
@Composable
fun WakeCard(app: PandaApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val profile by app.settings.activeProfile.collectAsState(initial = null)
    val profiles by app.settings.profiles.collectAsState(initial = emptyList())
    val target = profile?.takeIf { it.canWake } ?: return
    val pcName = target.name.ifBlank { "PC" }
    val scope = rememberCoroutineScope()
    var busy by remember(target.id) { mutableStateOf(false) }
    var status by remember(target.id) { mutableStateOf<String?>(null) }
    var ok by remember(target.id) { mutableStateOf<Boolean?>(null) }
    val colors = LocalPandaColors.current

    fun wake() {
        busy = true; ok = null; status = "Enviando paquete…"
        scope.launch {
            val mac = WakeOnLan.normalizeMac(target.wolMac)
            val (direct, relayed) = withContext(Dispatchers.IO) {
                val relays = profiles.filter { it.id != target.id && it.isConfigured }
                    .map { relayPc -> async { if (relay(relayPc, mac)) relayPc.name.ifBlank { "PC" } else null } }
                val d = runCatching { WakeOnLan.sendDirect(context, mac) }
                    .getOrElse { WakeOnLan.DirectResult(emptyList(), listOf(it.message ?: "error")) }
                d to relays.awaitAll().filterNotNull()
            }
            val routes = direct.sentVia.map { "celular ($it)" } + relayed
            val detail = if (direct.sent) "" else " Directo falló: ${direct.errors.joinToString("; ")}."
            val sentText = if (routes.isEmpty()) "No salió el paquete por ninguna vía.$detail"
                           else "Paquete enviado vía ${routes.joinToString(", ")}.$detail"
            val up = waitForBackend(target) { s ->
                status = "$sentText Esperando a $pcName… $s s"
            }
            ok = up
            status = if (up) "$sentText $pcName responde."
                     else "$sentText $pcName no respondió en $WAIT_BOOT_S s. Si estás fuera de casa, " +
                          "hace falta otro PC encendido en la LAN para reenviar el paquete."
            busy = false
        }
    }

    PandaCard(title = "ENCENDER :: wake-on-lan", accent = colors.green, modifier = modifier) {
        Text(
            "Enciende $pcName con un magic packet a ${WakeOnLan.normalizeMac(target.wolMac)}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { wake() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.green.copy(alpha = 0.2f),
                contentColor = colors.green,
            ),
        ) { Text(if (busy) "Encendiendo…" else "Encender $pcName") }
        status?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = when (ok) {
                    true -> colors.green
                    false -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** Pide a [pc] que reenvíe el paquete desde su LAN. */
private suspend fun relay(pc: Profile, mac: String): Boolean {
    val api = PandaApi(pc.baseUrl, pc.token)
    return try {
        withTimeoutOrNull(8_000) { runCatching { api.wakeRelay(mac).ok }.getOrDefault(false) } ?: false
    } finally {
        api.close()
    }
}

/** Sondea /health del PC hasta que conteste o pasen [WAIT_BOOT_S] segundos. */
private suspend fun waitForBackend(pc: Profile, onTick: (Int) -> Unit): Boolean {
    val api = PandaApi(pc.baseUrl, pc.token)
    val start = System.currentTimeMillis()
    try {
        while (true) {
            val elapsed = ((System.currentTimeMillis() - start) / 1000).toInt()
            if (elapsed >= WAIT_BOOT_S) return false
            onTick(elapsed)
            val up = withContext(Dispatchers.IO) {
                withTimeoutOrNull(5_000) { runCatching { api.health().ok }.getOrDefault(false) } ?: false
            }
            if (up) return true
            delay(5_000)
        }
    } finally {
        api.close()
    }
}
