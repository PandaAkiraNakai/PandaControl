package io.github.pandaakira.apppanda.ui.sudo
import io.github.pandaakira.apppanda.ui.theme.LocalPandaShapes
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors

import android.app.NotificationManager
import android.content.Context
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
fun SudoApprovalOverlay(app: PandaApp) {
    val pending by app.repository.pendingSudo.collectAsState()
    val req = pending ?: return

    val context = LocalContext.current
    val api by app.repository.api.collectAsState()
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
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
                            enabled = !busy,
                            modifier = Modifier.weight(1f).height(60.dp),
                        ) { Text("Rechazar") }
                        Button(
                            onClick = { decide(true) },
                            enabled = !busy,
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

private fun cancelSudoNotif(context: Context, rid: String) {
    val notifId = AlertsService.SUDO_NOTIF_BASE + (rid.hashCode() and 0x7fff)
    (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
        ?.cancel(notifId)
}
