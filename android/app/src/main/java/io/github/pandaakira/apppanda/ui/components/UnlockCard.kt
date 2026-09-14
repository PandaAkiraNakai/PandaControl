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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.SessionStateResponse

/**
 * Estado de la sesión gráfica del PC con el botón Desbloquear. Con la pantalla
 * bloqueada la desbloquea; si el PC quedó en la pantalla de inicio de sesión
 * (recién encendido o reiniciado) el backend inicia sesión con la contraseña
 * guardada para sudo, así arrancan Sunshine y el resto del escritorio. Pide
 * huella/credencial antes de mandar la acción.
 *
 * [onlyWhenNeeded] la oculta mientras la sesión está en uso (pantalla Inicio).
 * Si el backend no tiene /api/v1/session (versión vieja) no se muestra.
 */
@Composable
fun UnlockCard(
    app: PandaApp,
    onlyWhenNeeded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val api by app.repository.api.collectAsState()
    val activity = LocalContext.current as? FragmentActivity
    val activeProfile by app.settings.activeProfile.collectAsState(initial = null)
    val pcName = activeProfile?.name?.ifBlank { "PC" } ?: "PC"
    val exec = rememberActionExecutor { api }
    var session by remember(api) { mutableStateOf<SessionStateResponse?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var authing by remember { mutableStateOf(false) }

    PollingEffect(
        api = api, key = refresh, intervalMs = 10_000,
        onResult = { session = it }, onError = { if (it != null) session = null },
    ) { it.sessionState() }

    // Al terminar la acción, refrescar el estado sin esperar al próximo tick.
    LaunchedEffect(exec.busy) {
        if (!exec.busy && exec.ok != null) refresh++
    }

    val current = session ?: return
    if (onlyWhenNeeded && current.state == "unlocked" && exec.status == null) return

    val colors = LocalPandaColors.current
    val (title, text, accent) = when (current.state) {
        "login" -> Triple(
            "SESIÓN :: pantalla de login",
            "$pcName está en la pantalla de inicio de sesión. Desbloquear inicia " +
                "sesión con tu contraseña, igual que al escribirla.",
            colors.orange,
        )
        "locked" -> Triple(
            "SESIÓN :: bloqueada",
            "La sesión de $pcName tiene la pantalla bloqueada.",
            colors.yellow,
        )
        else -> Triple(
            "SESIÓN :: desbloqueada",
            "La sesión de $pcName está en uso.",
            colors.green,
        )
    }

    PandaCard(title = title, accent = accent, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (current.state != "unlocked") {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val unlock = { exec.run("Desbloquear") { it.powerAction("unlock") } }
                    val act = activity
                    if (act == null) {
                        unlock()
                    } else {
                        authing = true
                        confirmIdentity(
                            activity = act,
                            title = "Desbloquear $pcName",
                            subtitle = if (current.state == "login") "Iniciar sesión en $pcName"
                                       else "Quitar el bloqueo de pantalla",
                            description = "Cualquiera frente a $pcName podrá usar la " +
                                "sesión. Cancela para no desbloquear.",
                            onApproved = { authing = false; unlock() },
                            onDenied = { authing = false },
                        )
                    }
                },
                enabled = !exec.busy && !authing && api != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.green.copy(alpha = 0.2f),
                    contentColor = colors.green,
                ),
            ) { Text(if (exec.busy) "Desbloqueando…" else "Desbloquear") }
        }
        exec.status?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = when (exec.ok) {
                    true -> colors.green
                    false -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
