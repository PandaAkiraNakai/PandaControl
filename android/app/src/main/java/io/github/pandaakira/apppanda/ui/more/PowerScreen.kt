package io.github.pandaakira.apppanda.ui.more

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.ui.components.ActionResultBanner
import io.github.pandaakira.apppanda.ui.components.ConfirmDialog
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.components.rememberActionExecutor
import io.github.pandaakira.apppanda.ui.theme.PandaCyan
import io.github.pandaakira.apppanda.ui.theme.PandaOrange
import io.github.pandaakira.apppanda.ui.theme.PandaRed
import io.github.pandaakira.apppanda.ui.theme.PandaYellow

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
    var pending by remember { mutableStateOf<PowerOption?>(null) }

    val options = listOf(
        PowerOption("off", "Apagar", PandaRed,
            "Esto va a apagar la torre. ¿Estás seguro?"),
        PowerOption("reboot", "Reiniciar", PandaOrange,
            "Esto va a reiniciar la torre."),
        PowerOption("suspend", "Suspender", PandaYellow,
            "Suspender la torre (S3)."),
        PowerOption("lock", "Bloquear", PandaCyan,
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

        Spacer(Modifier.height(12.dp))
        ActionResultBanner(exec)
    }

    pending?.let { opt ->
        ConfirmDialog(
            title = "// ${opt.label.uppercase()}",
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
