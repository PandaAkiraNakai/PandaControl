package io.github.pandaakira.apppanda.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.data.NotifCategory
import io.github.pandaakira.apppanda.data.NotifGroup
import io.github.pandaakira.apppanda.data.PandaApi
import io.github.pandaakira.apppanda.service.AlertsService
import io.github.pandaakira.apppanda.ui.components.PandaCard
import io.github.pandaakira.apppanda.ui.components.ScreenHeader
import io.github.pandaakira.apppanda.ui.theme.PandaCyan
import io.github.pandaakira.apppanda.ui.theme.PandaGreen
import io.github.pandaakira.apppanda.ui.theme.PandaMagenta
import io.github.pandaakira.apppanda.ui.theme.PandaRed
import io.github.pandaakira.apppanda.ui.theme.PandaYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(app: PandaApp) {
    val cfg by app.settings.config.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8890") }
    var token by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }

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

    fun computeBaseUrl() = "http://${host.trim()}:${port.trim().ifBlank { "8890" }}"

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("SETTINGS", "backend · notificaciones · permisos")

        // ─── Backend ─────────────────────────────────────────────────────
        PandaCard(title = "BACKEND", accent = PandaYellow) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("Host") },
                placeholder = { Text("100.64.0.5  (IP Tailscale)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("Puerto") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Bearer token (opcional con Tailscale auth)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        busy = true; testResult = null; testOk = null
                        scope.launch {
                            val api = PandaApi(computeBaseUrl(), token.trim())
                            try {
                                val sys = withContext(Dispatchers.IO) { api.systemStatus() }
                                testOk = true
                                testResult = "OK · ${sys.hostname} · ${sys.cpu.cores} cores"
                            } catch (e: Exception) {
                                testOk = false
                                testResult = "fallo: ${e.message?.take(100) ?: e::class.simpleName}"
                            } finally {
                                api.close(); busy = false
                            }
                        }
                    },
                    enabled = !busy && host.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(if (busy) "Probando…" else "Probar") }
                Button(
                    onClick = {
                        scope.launch {
                            app.settings.save(computeBaseUrl(), token.trim())
                            testResult = "guardado"; testOk = true
                        }
                    },
                    enabled = !busy && host.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Guardar") }
            }
            testResult?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (testOk) {
                        true -> PandaGreen
                        false -> PandaRed
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }

        // ─── Notificaciones push ─────────────────────────────────────────
        PushNotificationsCard(app = app)

        // ─── Qué notificar (filtro por categoría) ────────────────────────
        NotificationFilterCard(app = app)

        // ─── Permisos del sistema ────────────────────────────────────────
        PermissionsCard()

        // ─── Acerca de ───────────────────────────────────────────────────
        PandaCard(title = "ABOUT", accent = PandaMagenta) {
            Text(
                "Panda Control · panel para sergiotorre desde el celular.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Conectado vía Tailscale tailnet. Auth: identidad Tailscale (PandaAkiraNakai@github) o Bearer token.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PushNotificationsCard(app: PandaApp) {
    val context = LocalContext.current
    val pushEnabled by app.settings.pushEnabled.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scope.launch {
                app.settings.setPushEnabled(true)
                AlertsService.start(context)
            }
        }
    }

    PandaCard(title = "NOTIFICACIONES PUSH", accent = PandaCyan) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    "Push de la torre",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Mantiene un service en background conectado al SSE. Dispara notificaciones del sistema para alertas CPU/RAM/temps, servicios failed, boot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = pushEnabled,
                onCheckedChange = { wanted ->
                    if (wanted) {
                        val needs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        val granted = !needs || ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            scope.launch {
                                app.settings.setPushEnabled(true)
                                AlertsService.start(context)
                            }
                        } else {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        scope.launch {
                            app.settings.setPushEnabled(false)
                            AlertsService.stop(context)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun NotificationFilterCard(app: PandaApp) {
    val muted by app.settings.mutedNotifs.collectAsState(initial = emptySet())
    val pushEnabled by app.settings.pushEnabled.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    PandaCard(title = "QUÉ NOTIFICAR", accent = PandaCyan) {
        Text(
            "Elegí qué eventos disparan notificación. Las solicitudes sudo siempre llegan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!pushEnabled) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Activá las notificaciones push de arriba para que esto tenga efecto.",
                style = MaterialTheme.typography.labelSmall,
                color = PandaYellow,
            )
        }
        NotifGroup.entries.forEach { group ->
            Spacer(Modifier.height(12.dp))
            Text(
                group.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = PandaCyan,
            )
            Spacer(Modifier.height(2.dp))
            NotifCategory.entries.filter { it.group == group }.forEach { cat ->
                NotifToggleRow(
                    label = cat.label,
                    checked = cat.id !in muted,
                    enabled = pushEnabled,
                ) { wanted ->
                    scope.launch { app.settings.setNotifEnabled(cat.id, wanted) }
                }
            }
        }
    }
}

@Composable
private fun NotifToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionsCard() {
    val context = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* user response */ }

    val notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    var batteryIgnored by remember {
        mutableStateOf(pm?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }

    PandaCard(title = "PERMISOS", accent = PandaYellow) {
        PermissionRow(
            label = "Mostrar notificaciones",
            granted = notifGranted,
            onRequest = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        PermissionRow(
            label = "Ignorar optimización de batería",
            granted = batteryIgnored,
            onRequest = {
                @SuppressLint("BatteryLife")
                val intent = Intent(
                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val fallback = Intent(
                        AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(fallback)
                }
                batteryIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val intent = Intent(
                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Abrir ajustes de la app en Android")
        }
        Text(
            "Si las notifs no llegan con la pantalla apagada: pedí la excepción de batería de arriba. " +
                "Honor / Xiaomi además requieren activar manualmente \"Inicio automático\" y poner la batería de la app en \"Sin restricciones\" desde los ajustes del sistema.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
        if (granted) {
            Text("✓ permitido",
                style = MaterialTheme.typography.bodyMedium, color = PandaGreen)
        } else {
            OutlinedButton(onClick = onRequest) { Text("Pedir") }
        }
    }
}
