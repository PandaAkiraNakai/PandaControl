package io.github.pandaakira.apppanda.ui.more

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.data.models.AudioMaster
import io.github.pandaakira.apppanda.ui.components.ActionExecutor
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors
import kotlinx.coroutines.launch

// ─── Volumen maestro (Reproductor y Rápido) ──────────────────────────────────

/**
 * Volumen maestro del sink por defecto: slider + mute. [onChanged] pide al
 * contenedor que recargue el audio tras silenciar/activar.
 */
@Composable
internal fun MasterVolumeCard(master: AudioMaster, exec: ActionExecutor, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val accent = LocalPandaColors.current.magenta
    val muted = master.muted == true
    var sliderVol by remember(master.volumePct, master.muted) {
        mutableStateOf((master.volumePct ?: 0).toFloat())
    }
    PandaCard(title = "VOLUMEN", accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                exec.run(if (muted) "Activar sonido" else "Silenciar") {
                    it.setMute("toggle")
                }
                scope.launch {
                    kotlinx.coroutines.delay(300); onChanged()
                }
            }) {
                Text(
                    if (muted) "🔇" else "🔊",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Slider(
                value = sliderVol,
                onValueChange = { sliderVol = it },
                onValueChangeFinished = {
                    exec.run("Volumen ${sliderVol.toInt()}%") {
                        it.setVolume(sliderVol.toInt())
                    }
                },
                valueRange = 0f..150f,
                enabled = !muted && !exec.busy,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${sliderVol.toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
                        else accent,
                modifier = Modifier.width(52.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}
