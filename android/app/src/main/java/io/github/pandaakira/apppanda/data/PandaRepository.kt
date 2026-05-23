package io.github.pandaakira.apppanda.data

import io.github.pandaakira.apppanda.data.models.SseEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onEach

/** Singleton liviano que mantiene el API y el stream SSE, reconstruyéndolo
 *  cuando cambia la config (token o baseUrl). */
class PandaRepository(
    private val settings: Settings,
    scope: CoroutineScope,
) {
    private val _api = MutableStateFlow<PandaApi?>(null)
    val api: StateFlow<PandaApi?> = _api.asStateFlow()

    /** Timestamp (epoch ms) del último evento SSE recibido. Permite a la
     *  UI mostrar "conectado" si fue hace menos de N segundos. */
    private val _lastEventAt = MutableStateFlow<Long>(0)
    val lastEventAt: StateFlow<Long> = _lastEventAt.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            settings.config.collect { cfg ->
                _api.value?.close()
                _api.value = if (cfg.isConfigured) PandaApi(cfg.baseUrl, cfg.token) else null
            }
        }
    }

    /** Stream SSE que se reconecta automáticamente con backoff exponencial
     *  acotado a 30 s. Filtra los `hello` (sólo señalización). */
    val events: Flow<SseEvent> = channelFlow {
        while (true) {
            val current = _api.first { it != null }!!
            try {
                current.events()
                    .onEach { _lastEventAt.value = System.currentTimeMillis() }
                    .collect { evt ->
                        if (evt.type != "hello") send(evt)
                    }
            } catch (_: Exception) {
                // ignoramos; reintento abajo
            }
            delay(reconnectBackoffMs.also {
                reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(30_000)
            })
        }
    }

    @Volatile
    private var reconnectBackoffMs: Long = 1_000

    fun resetBackoff() {
        reconnectBackoffMs = 1_000
    }
}
