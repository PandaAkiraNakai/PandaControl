package io.github.pandaakira.apppanda.data

import io.github.pandaakira.apppanda.data.models.SseEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/** Pending sudo approval request — vive en memoria, no se persiste. */
data class SudoPending(
    val rid: String,
    val prompt: String,
    val command: String,
    val timeoutS: Int,
    val receivedAtMs: Long,
)

/** Singleton liviano que mantiene el API y el stream SSE, reconstruyéndolo
 *  cuando cambia la config (token o baseUrl). */
class PandaRepository(
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _api = MutableStateFlow<PandaApi?>(null)
    val api: StateFlow<PandaApi?> = _api.asStateFlow()

    /** Timestamp (epoch ms) del último evento SSE recibido. Permite a la
     *  UI mostrar "conectado" si fue hace menos de N segundos. */
    private val _lastEventAt = MutableStateFlow<Long>(0)
    val lastEventAt: StateFlow<Long> = _lastEventAt.asStateFlow()

    /** Última vez que se leyó CUALQUIER línea del stream SSE (incluido
     *  heartbeat). Si esto avanza pero lastEventAt queda, el parser falla. */
    private val _lastByteAt = MutableStateFlow<Long>(0)
    val lastByteAt: StateFlow<Long> = _lastByteAt.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _parseErrorCount = MutableStateFlow(0)
    val parseErrorCount: StateFlow<Int> = _parseErrorCount.asStateFlow()

    /** Última solicitud de sudo pendiente recibida por SSE. La UI muestra
     *  un dialog modal cuando esto != null. AlertsService la setea cuando
     *  llega un evento sudo_request; el dialog la limpia al decidir o al
     *  vencer el timeout. */
    private val _pendingSudo = MutableStateFlow<SudoPending?>(null)
    val pendingSudo: StateFlow<SudoPending?> = _pendingSudo.asStateFlow()

    fun setPendingSudo(req: SudoPending) { _pendingSudo.value = req }
    fun clearPendingSudo() { _pendingSudo.value = null }

    init {
        scope.launch(Dispatchers.IO) {
            settings.config.collect { cfg ->
                _api.value?.close()
                _api.value = if (cfg.isConfigured) PandaApi(cfg.baseUrl, cfg.token) else null
            }
        }
    }

    @Volatile
    private var reconnectBackoffMs: Long = 1_000

    fun resetBackoff() {
        reconnectBackoffMs = 1_000
    }

    /** Source flow: una sola conexión SSE compartida entre todos los
     *  colectores (HomeScreen + AlertsService). Si nadie escucha por 5s,
     *  la conexión se cierra. Cuando alguien vuelve a escuchar, se
     *  reabre. Esto evita las N conexiones simultáneas anteriores. */
    private val sourceEvents: Flow<SseEvent> = channelFlow {
        while (true) {
            val current = _api.first { it != null }!!
            try {
                current.events(onByte = { _lastByteAt.value = System.currentTimeMillis() })
                    .collect { evt ->
                        if (evt.type == "__parse_error__") {
                            _parseErrorCount.value = _parseErrorCount.value + 1
                            _lastError.value = "parse: ${evt.title}"
                            return@collect
                        }
                        _lastEventAt.value = System.currentTimeMillis()
                        _lastError.value = null
                        resetBackoff()
                        if (evt.type != "hello") send(evt)
                    }
            } catch (e: Exception) {
                _lastError.value = "conn: ${e.message?.take(80) ?: e::class.simpleName}"
            }
            delay(reconnectBackoffMs.also {
                reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(30_000)
            })
        }
    }

    val events: SharedFlow<SseEvent> = sourceEvents.shareIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        replay = 0,
    )
}
