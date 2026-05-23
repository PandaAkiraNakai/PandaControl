package io.github.pandaakira.apppanda.sudo

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.theme.PandaControlTheme
import io.github.pandaakira.apppanda.ui.theme.PandaGreen
import io.github.pandaakira.apppanda.ui.theme.PandaRed
import io.github.pandaakira.apppanda.ui.theme.PandaYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity que se levanta sobre el lockscreen cuando llega un sudo_request.
 * Pide aprobar o rechazar. Si el usuario no responde en `timeoutS` segundos,
 * se cierra sin tomar decisión (el backend marca como expired y el askpass
 * binary cae al fallback).
 */
class SudoApprovalActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RID = "rid"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TIMEOUT_S = "timeout_s"
        const val EXTRA_NOTIF_ID = "notif_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Encender pantalla, mostrar sobre lockscreen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        // Pedir al keyguard que sea dismissable (NO desbloquea — el user lo hará).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        }

        val rid = intent.getStringExtra(EXTRA_RID).orEmpty()
        val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        val timeoutS = intent.getIntExtra(EXTRA_TIMEOUT_S, 60)
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)

        setContent {
            PandaControlTheme {
                SudoApprovalContent(
                    rid = rid,
                    prompt = prompt,
                    command = command,
                    timeoutS = timeoutS,
                    onDone = {
                        if (notifId >= 0) {
                            (getSystemService(Context.NOTIFICATION_SERVICE)
                                as? NotificationManager)?.cancel(notifId)
                        }
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun SudoApprovalContent(
    rid: String,
    prompt: String,
    command: String,
    timeoutS: Int,
    onDone: () -> Unit,
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as PandaApp
    val api by app.repository.api.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var remaining by remember { mutableIntStateOf(timeoutS) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (remaining > 0 && !busy && result == null) {
            delay(1_000)
            remaining--
        }
        if (result == null) {
            // Timeout: no enviamos decisión (el backend marca como expired
            // por sí solo cuando vence el wait del askpass).
            onDone()
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
                withContext(Dispatchers.IO) { client.sudoDecision(rid, approved) }
                result = if (approved) "APROBADO" else "RECHAZADO"
                delay(800)
                onDone()
            } catch (e: Exception) {
                result = "error: ${e.message?.take(60) ?: e::class.simpleName}"
                busy = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, PandaYellow, RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "// SUDO REQUEST",
                style = MaterialTheme.typography.labelSmall,
                color = PandaYellow,
            )
            Text(
                "Aprobar elevación de privilegios?",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (prompt.isNotBlank()) {
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (command.isNotBlank()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "// COMANDO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        command,
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
                        "APROBADO" -> PandaGreen
                        "RECHAZADO" -> PandaRed
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
                            containerColor = PandaGreen.copy(alpha = 0.3f),
                            contentColor = PandaGreen,
                        ),
                        modifier = Modifier.weight(1f).height(60.dp),
                    ) { Text("Aprobar") }
                }
            }
        }
    }
}
