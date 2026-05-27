package io.github.pandaakira.apppanda.ui.sudo
import io.github.pandaakira.apppanda.ui.theme.LocalPandaShapes
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import io.github.pandaakira.apppanda.ui.theme.PandaTheme
import io.github.pandaakira.apppanda.ui.themes.ThemedBackground

import android.app.NotificationManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.service.AlertsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Overlay modal de aprobación sudo. Se muestra sobre cualquier pantalla
 * de la app cuando hay una solicitud pendiente en `repository.pendingSudo`.
 * Reemplaza a la antigua SudoApprovalActivity — al estar dentro de la app,
 * comparte estado y backstack normales.
 */
@Composable
fun SudoApprovalOverlay(app: PandaApp, theme: PandaTheme) {
    val pending by app.repository.pendingSudo.collectAsState()
    val req = pending ?: return

    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val api by app.repository.api.collectAsState()
    val activeProfile by app.settings.activeProfile.collectAsState(initial = null)
    val pcName = activeProfile?.name?.ifBlank { "PC" } ?: "PC"
    val scope = rememberCoroutineScope()

    val totalS = req.timeoutS
    var remaining by remember(req.rid) {
        mutableIntStateOf(
            (totalS - ((System.currentTimeMillis() - req.receivedAtMs) / 1000).toInt())
                .coerceAtLeast(0),
        )
    }
    var busy by remember(req.rid) { mutableStateOf(false) }
    var result by remember(req.rid) { mutableStateOf<String?>(null) }
    // Mientras el sheet biométrico está arriba: bloquea los botones para no
    // lanzar varios prompts. No usa `busy` (ese marca el envío de la decisión).
    var authing by remember(req.rid) { mutableStateOf(false) }

    LaunchedEffect(req.rid) {
        while (remaining > 0 && !busy && result == null) {
            delay(1_000)
            remaining = (totalS - ((System.currentTimeMillis() - req.receivedAtMs) / 1000).toInt())
                .coerceAtLeast(0)
        }
        if (result == null) {
            // Timeout: limpiar el pending sin enviar decisión (el backend
            // marca expired solo cuando vence el wait del askpass).
            cancelSudoNotif(context, req.rid)
            app.repository.clearPendingSudo()
        }
    }

    fun decide(approved: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val client = api
            if (client == null) {
                result = "sin backend"
                busy = false
                return@launch
            }
            try {
                withContext(Dispatchers.IO) { client.sudoDecision(req.rid, approved) }
                result = if (approved) "APROBADO" else "RECHAZADO"
                delay(700)
                cancelSudoNotif(context, req.rid)
                app.repository.clearPendingSudo()
            } catch (e: Exception) {
                result = "error: ${e.message?.take(60) ?: e::class.simpleName}"
                busy = false
            }
        }
    }

    Dialog(
        onDismissRequest = { /* no se cierra con back / tap fuera */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // El Dialog vive en su propia ventana, así que el ThemedBackground que
        // envuelve al AppNav no llega hasta acá. Lo aplicamos de nuevo para que
        // el popup muestre el mismo fondo del tema (color sólido, imagen o
        // efecto animado) y no quede como un panel plano ajeno al tema.
        ThemedBackground(app = app, theme = theme) {
          Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(LocalPandaShapes.current.corner))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(LocalPandaShapes.current.border, LocalPandaColors.current.yellow, RoundedCornerShape(LocalPandaShapes.current.corner))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "// SUDO REQUEST",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalPandaColors.current.yellow,
                )
                Text(
                    "¿Aprobar elevación de privilegios?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (req.prompt.isNotBlank()) {
                    Text(
                        req.prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (req.command.isNotBlank()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "// COMANDO",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            req.command,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Text(
                    "Timeout en ${remaining}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))

                if (result != null) {
                    Text(
                        result!!,
                        style = MaterialTheme.typography.titleMedium,
                        color = when (result) {
                            "APROBADO" -> LocalPandaColors.current.green
                            "RECHAZADO" -> LocalPandaColors.current.red
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { decide(false) },
                            enabled = !busy && !authing,
                            modifier = Modifier.weight(1f).height(60.dp),
                        ) { Text("Rechazar") }
                        Button(
                            onClick = {
                                // Aprobar exige biometría/credencial. Solo al
                                // confirmar identidad se envía la decisión.
                                if (busy || authing) return@Button
                                val act = activity
                                if (act == null) {
                                    decide(true)
                                } else {
                                    authing = true
                                    promptSudoBiometric(
                                        activity = act,
                                        command = req.command,
                                        pcName = pcName,
                                        onApproved = { authing = false; decide(true) },
                                        onDenied = { authing = false },
                                    )
                                }
                            },
                            enabled = !busy && !authing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LocalPandaColors.current.green.copy(alpha = 0.3f),
                                contentColor = LocalPandaColors.current.green,
                            ),
                            modifier = Modifier.weight(1f).height(60.dp),
                        ) { Text("Aprobar") }
                    }
                }
            }
          }
        }
    }
}

private fun cancelSudoNotif(context: Context, rid: String) {
    val notifId = AlertsService.SUDO_NOTIF_BASE + (rid.hashCode() and 0x7fff)
    (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
        ?.cancel(notifId)
}

/**
 * Pide confirmar identidad (huella o, como respaldo, PIN/patrón del
 * dispositivo) antes de aprobar una elevación de privilegios. `onApproved`
 * solo se invoca si la autenticación tuvo éxito; cualquier cancelación o error
 * llama a `onDenied` sin aprobar.
 *
 * Si el dispositivo no tiene ningún método de bloqueo configurado, no podemos
 * exigir biometría — aprobamos directo (el factor de seguridad real es ya
 * tener la app y el token).
 */
private fun promptSudoBiometric(
    activity: FragmentActivity,
    command: String,
    pcName: String,
    onApproved: () -> Unit,
    onDenied: () -> Unit,
) {
    val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(allowed) !=
        BiometricManager.BIOMETRIC_SUCCESS
    ) {
        onApproved()
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult,
            ) = onApproved()

            override fun onAuthenticationError(
                errorCode: Int,
                errString: CharSequence,
            ) = onDenied()
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Aprobar elevación sudo")
        .setSubtitle(
            if (command.isNotBlank()) "Comando: ${command.take(70)}"
            else "Elevación de privilegios en $pcName",
        )
        .setDescription(
            "Al confirmar tu identidad APRUEBAS esta solicitud de sudo en " +
                "$pcName. Cancela para no aprobar.",
        )
        .setAllowedAuthenticators(allowed)
        .build()
    prompt.authenticate(info)
}
