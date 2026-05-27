package io.github.pandaakira.apppanda.ui.more
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.components.ActionResultBanner
import io.github.pandaakira.apppanda.ui.components.ConfirmDialog
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.components.pandaDeco
import io.github.pandaakira.apppanda.ui.components.rememberActionExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PowerOption(
    val action: String,
    val label: String,
    val color: Color,
    val confirmText: String,
)

@Composable
fun PowerScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    val exec = rememberActionExecutor { api }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PowerOption?>(null) }
    var inhibited by remember { mutableStateOf(false) }
    var inhibitRefresh by remember { mutableStateOf(0) }

    LaunchedEffect(api, inhibitRefresh) {
        val current = api ?: return@LaunchedEffect
        inhibited = withContext(Dispatchers.IO) {
            runCatching { current.inhibitState().active }.getOrDefault(false)
        }
    }

    val options = listOf(
        PowerOption("off", "Apagar", LocalPandaColors.current.red,
            "Esto va a apagar la torre. ¿Estás seguro?"),
        PowerOption("reboot", "Reiniciar", LocalPandaColors.current.orange,
            "Esto va a reiniciar la torre."),
        PowerOption("suspend", "Suspender", LocalPandaColors.current.yellow,
            "Suspender la torre (S3)."),
        PowerOption("lock", "Bloquear", LocalPandaColors.current.cyan,
            "Bloquear la sesión gráfica."),
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("POWER :: actions", "confirmación 2-pasos requerida")
        options.forEach { opt ->
            Button(
                onClick = { pending = opt },
                enabled = !exec.busy && api != null,
                modifier = Modifier.fillMaxWidth().height(72.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = opt.color.copy(alpha = 0.2f),
                    contentColor = opt.color,
                ),
            ) {
                Text(opt.label, style = MaterialTheme.typography.titleLarge)
            }
        }

        Spacer(Modifier.height(4.dp))
        PandaCard(
            title = "INHIBIR SUSPENSIÓN",
            accent = if (inhibited) LocalPandaColors.current.green
                     else LocalPandaColors.current.cyan,
        ) {
            Text(
                if (inhibited) "Activo: la torre NO se suspenderá ni entrará en idle."
                else "Evita que la torre se suspenda (útil mientras descarga algo).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val target = !inhibited
                    exec.run(if (target) "Inhibir suspensión" else "Permitir suspensión") {
                        it.setInhibit(target)
                    }
                    scope.launch { kotlinx.coroutines.delay(300); inhibitRefresh++ }
                },
                enabled = !exec.busy && api != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = (if (inhibited) LocalPandaColors.current.green
                                      else LocalPandaColors.current.cyan).copy(alpha = 0.2f),
                    contentColor = if (inhibited) LocalPandaColors.current.green
                                   else LocalPandaColors.current.cyan,
                ),
            ) { Text(if (inhibited) "Desactivar" else "Activar") }
        }

        Spacer(Modifier.height(12.dp))
        ActionResultBanner(exec)
    }

    pending?.let { opt ->
        ConfirmDialog(
            title = pandaDeco("${opt.label.uppercase()}"),
            message = opt.confirmText,
            confirmLabel = opt.label,
            onConfirm = {
                exec.run(opt.label) { it.powerAction(opt.action) }
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
}
