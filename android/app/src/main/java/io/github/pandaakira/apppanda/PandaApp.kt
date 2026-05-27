package io.github.pandaakira.apppanda

import android.app.Application
import io.github.pandaakira.apppanda.data.PandaRepository
import io.github.pandaakira.apppanda.data.Settings
import io.github.pandaakira.apppanda.service.AlertsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PandaApp : Application() {
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    val settings: Settings by lazy { Settings(applicationContext) }
    val repository: PandaRepository by lazy { PandaRepository(settings, appScope) }

    override fun onCreate() {
        super.onCreate()
        // Si el usuario ya activó notificaciones push antes, levantar el
        // service automáticamente al arrancar la app.
        appScope.launch {
            try {
                // Instalaciones previas guardaban un único backend (base_url/
                // token sueltos); convertirlo en el primer perfil.
                settings.migrateLegacyIfNeeded()
                val enabled = settings.pushEnabled.first()
                val cfg = settings.config.first()
                if (enabled && cfg.isConfigured) {
                    AlertsService.start(applicationContext)
                }
            } catch (_: Exception) {
            }
        }
    }
}
