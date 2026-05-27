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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.pandaakira.apppanda.data.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingScreen(app: PandaApp, onSaved: () -> Unit) {
    val activeProfile by app.settings.activeProfile.collectAsState(initial = null)
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8890") }
    var token by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(activeProfile) {
        activeProfile?.let { p ->
            if (p.baseUrl.isNotBlank() && host.isBlank()) {
                val regex = Regex("""https?://([^:/]+)(?::(\d+))?""")
                val m = regex.matchEntire(p.baseUrl)
                if (m != null) {
                    host = m.groupValues[1]
                    port = m.groupValues[2].ifBlank { "8890" }
                }
                if (token.isBlank()) token = p.token
            }
            if (name.isBlank()) name = p.name
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
            "Configura el backend del PC. host = IP Tailscale (o 127.0.0.1 si el PC es este dispositivo).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nombre del PC") },
            placeholder = { Text("Torre, Laptop…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            placeholder = { Text("100.64.0.5  (IP Tailscale)") },
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
            placeholder = { Text("hex 64 chars — déjalo vacío si el PC usa Tailscale auth") },
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
                    val url = computeBaseUrl()
                    val tok = token.trim()
                    val n = name.trim().ifBlank { "Mi PC" }
                    val existing = activeProfile
                    val profile = if (existing != null) {
                        existing.copy(name = n, baseUrl = url, token = tok)
                    } else {
                        Profile(Profile.newId(), n, url, tok)
                    }
                    app.settings.upsertProfile(profile)
                    onSaved()
                }
            },
            enabled = testOk == true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Guardar y entrar")
        }

        if (activeProfile != null) {
            TextButton(
                onClick = {
                    scope.launch {
                        app.settings.clear()
                        name = ""; host = ""; port = "8890"; token = ""
                        testResult = null; testOk = null
                    }
                },
            ) { Text("Borrar configuración guardada") }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Más ajustes (notificaciones, permisos) están en Sistema → Ajustes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
