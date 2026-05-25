package io.github.pandaakira.apppanda.ui.onboarding
import io.github.pandaakira.apppanda.ui.theme.LocalPandaColors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.pandaakira.apppanda.service.AlertsService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.PandaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingScreen(app: PandaApp, onSaved: () -> Unit) {
    val cfg by app.settings.config.collectAsState(initial = null)
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8890") }
    var token by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cfg) {
        cfg?.let { c ->
            if (c.baseUrl.isNotBlank() && host.isBlank()) {
                val regex = Regex("""https?://([^:/]+)(?::(\d+))?""")
                val m = regex.matchEntire(c.baseUrl)
                if (m != null) {
                    host = m.groupValues[1]
                    port = m.groupValues[2].ifBlank { "8890" }
                }
                if (token.isBlank()) token = c.token
            }
        }
    }

    fun computeBaseUrl(): String {
        val h = host.trim()
        val p = port.trim().ifBlank { "8890" }
        return "http://$h:$p"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "// PANDA :: ENROLL",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Configura el backend de la torre. host = IP Tailscale (o 127.0.0.1 si la torre es este dispositivo).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            placeholder = { Text("100.64.0.5  (IP Tailscale de tu torre)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            label = { Text("Puerto") },
            placeholder = { Text("8890") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Bearer token (opcional con Tailscale)") },
            placeholder = { Text("hex 64 chars — déjalo vacío si tu torre usa Tailscale auth") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                busy = true
                testResult = null
                testOk = null
                scope.launch {
                    val api = PandaApi(computeBaseUrl(), token.trim())
                    try {
                        val sys = withContext(Dispatchers.IO) { api.systemStatus() }
                        testOk = true
                        testResult = "OK · ${sys.hostname} · ${sys.cpu.cores} cores · ${"%.0f".format(sys.ram.totalG)} GB RAM"
                    } catch (e: Exception) {
                        testOk = false
                        testResult = "fallo: ${e.message?.take(120) ?: e::class.simpleName}"
                    } finally {
                        api.close()
                        busy = false
                    }
                }
            },
            enabled = !busy && host.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Probando…" else "Probar conexión")
        }

        testResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = when (testOk) {
                    true -> LocalPandaColors.current.green
                    false -> LocalPandaColors.current.red
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }

        Button(
            onClick = {
                scope.launch {
                    app.settings.save(computeBaseUrl(), token.trim())
                    onSaved()
                }
            },
            enabled = testOk == true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Guardar y entrar")
        }

        if (cfg?.isConfigured == true) {
            TextButton(
                onClick = {
                    scope.launch {
                        app.settings.clear()
                        host = ""; port = "8890"; token = ""
                        testResult = null; testOk = null
                    }
                },
            ) { Text("Borrar configuración guardada") }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Más ajustes (notificaciones, permisos) están en Modules → Ajustes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
