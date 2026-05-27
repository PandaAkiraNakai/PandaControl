package io.github.pandaakira.apppanda.ui.more
import io.github.pandaakira.apppanda.ui.theme.LocalPandaShapes
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.models.TerminalResponse
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TerminalScreen(app: PandaApp) {
    val api by app.repository.api.collectAsState()
    val scope = rememberCoroutineScope()

    var cmd by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<TerminalResponse?>(null) }
    var running by remember { mutableStateOf(false) }

    fun runCmd() {
        val current = api ?: return
        val c = cmd.trim()
        if (c.isBlank() || running) return
        running = true
        scope.launch {
            result = try {
                withContext(Dispatchers.IO) { current.terminalRun(c) }
            } catch (e: Exception) {
                TerminalResponse(error = e.message?.take(120) ?: e::class.simpleName, exit = -1)
            }
            running = false
        }
    }

    Column(
        Modifier.fillMaxSize().imePadding().padding(16.dp),
    ) {
        ScreenHeader("TERMINAL", "bash -lc · como sergioc · sin sudo")
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                placeholder = { Text("comando…") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { runCmd() },
                enabled = cmd.isNotBlank() && !running && api != null,
                modifier = Modifier.padding(start = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LocalPandaColors.current.green.copy(alpha = 0.3f),
                    contentColor = LocalPandaColors.current.green,
                ),
            ) { Text(if (running) "…" else "Ejecutar") }
        }

        Spacer(Modifier.height(12.dp))

        result?.let { r ->
            val accent = when {
                r.error != null -> MaterialTheme.colorScheme.error
                r.exit == 0 -> LocalPandaColors.current.green
                else -> LocalPandaColors.current.yellow
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("exit ${r.exit}", style = MaterialTheme.typography.labelMedium, color = accent)
                if (r.truncated) Text("· salida truncada",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(LocalPandaShapes.current.corner))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(LocalPandaShapes.current.border, accent.copy(alpha = 0.4f),
                        RoundedCornerShape(LocalPandaShapes.current.corner))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                r.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                }
                if (r.stdout.isNotEmpty()) {
                    Text(r.stdout,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface)
                }
                if (r.stderr.isNotEmpty()) {
                    Text(r.stderr,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = LocalPandaColors.current.yellow)
                }
                if (r.error == null && r.stdout.isEmpty() && r.stderr.isEmpty()) {
                    Text("(sin salida)", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
