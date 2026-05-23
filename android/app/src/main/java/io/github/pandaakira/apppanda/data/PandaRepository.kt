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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pending sudo approval request — vive en memoria, no se persiste. */
data class SudoPending(
    val rid: String,
    val prompt: String,
    val command: String,
    val timeoutS: Int,
    val receivedAtMs: Long,
)

/** Estado del chat IA. Mensajes viven en memoria — el contenido de la
 *  conversación lo guarda Claude Code via session_id; perderlo al cerrar
 *  la app es esperado y coherente con el bot Telegram viejo. */
enum class AIRole { User, Assistant }

data class AIMessage(
    val id: String,
    val role: AIRole,
    var content: String,
    val tools: MutableList<String> = mutableListOf(),
    var done: Boolean = false,
    var durationS: Double? = null,
    var error: String? = null,
)

data class AIChatState(
    val messages: List<AIMessage> = emptyList(),
    val busy: Boolean = false,
    val sessionId: String? = null,
    val model: String = "default",
    val currentTurnId: String? = null,
    val lastError: String? = null,
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

    // ─── Estado IA ───────────────────────────────────────────────────────

    private val _aiChat = MutableStateFlow(AIChatState())
    val aiChat: StateFlow<AIChatState> = _aiChat.asStateFlow()

    private fun appendDelta(turnId: String?, delta: String) {
        if (delta.isEmpty()) return
        _aiChat.update { st ->
            val msgs = st.messages.toMutableList()
            val last = msgs.lastOrNull()
            if (last != null && last.role == AIRole.Assistant && !last.done) {
                last.content = last.content + delta
                st.copy(messages = msgs)
            } else {
                val newMsg = AIMessage(
                    id = turnId ?: System.currentTimeMillis().toString(),
                    role = AIRole.Assistant,
                    content = delta,
                )
                st.copy(messages = msgs + newMsg)
            }
        }
    }

    private fun appendTool(turnId: String?, line: String) {
        if (line.isBlank()) return
        _aiChat.update { st ->
            val msgs = st.messages.toMutableList()
            val last = msgs.lastOrNull()
            if (last != null && last.role == AIRole.Assistant && !last.done) {
                last.tools.add(line)
                st.copy(messages = msgs)
            } else {
                val newMsg = AIMessage(
                    id = turnId ?: System.currentTimeMillis().toString(),
                    role = AIRole.Assistant,
                    content = "",
                    tools = mutableListOf(line),
                )
                st.copy(messages = msgs + newMsg)
            }
        }
    }

    private fun finishTurn(
        turnId: String?, ok: Boolean,
        durationS: Double?, error: String?, sessionId: String?,
    ) {
        _aiChat.update { st ->
            val msgs = st.messages.toMutableList()
            // Si hubo error y nunca emitimos texto, crear un mensaje placeholder
            val last = msgs.lastOrNull()
            if (!ok && (last == null || last.role != AIRole.Assistant || last.done)) {
                msgs.add(
                    AIMessage(
                        id = turnId ?: System.currentTimeMillis().toString(),
                        role = AIRole.Assistant,
                        content = "",
                        done = true,
                        error = error ?: "error desconocido",
                    ),
                )
            } else if (last != null && last.role == AIRole.Assistant && !last.done) {
                last.done = true
                last.durationS = durationS
                last.error = if (!ok) error else null
            }
            st.copy(
                messages = msgs,
                busy = false,
                currentTurnId = null,
                sessionId = sessionId ?: st.sessionId,
                lastError = if (!ok) error else null,
            )
        }
    }

    private fun updateAiState(
        busy: Boolean, sessionId: String?, model: String?, turnId: String?,
    ) {
        _aiChat.update { st ->
            st.copy(
                busy = busy,
                sessionId = sessionId ?: st.sessionId,
                model = model ?: st.model,
                currentTurnId = turnId ?: st.currentTurnId.takeIf { busy },
            )
        }
    }

    fun aiAddUserMessage(content: String, turnId: String?) {
        if (content.isBlank()) return
        _aiChat.update { st ->
            val msgs = st.messages.toMutableList()
            msgs.add(
                AIMessage(
                    id = turnId ?: System.currentTimeMillis().toString(),
                    role = AIRole.User,
                    content = content,
                    done = true,
                ),
            )
            st.copy(messages = msgs, busy = true, currentTurnId = turnId)
        }
    }

    fun aiClearMessages() {
        _aiChat.update { it.copy(messages = emptyList(), lastError = null) }
    }

    fun aiSetLastError(error: String) {
        _aiChat.update { it.copy(lastError = error) }
    }

    fun aiSyncFromState(
        busy: Boolean, sessionId: String?, model: String, turnId: String?,
    ) {
        _aiChat.update {
            it.copy(
                busy = busy,
                sessionId = sessionId ?: it.sessionId,
                model = model,
                currentTurnId = turnId,
            )
        }
    }

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

    // Tiene que estar DESPUÉS de `val events` — los init blocks corren en
    // orden con las property initializations y referenciar `events` antes
    // de su línea tira NPE.
    init {
        scope.launch {
            events.collect { evt ->
                when (evt.type) {
                    "ai_chunk" -> appendDelta(evt.turnId, evt.delta.orEmpty())
                    "ai_tool" -> appendTool(
                        evt.turnId,
                        (evt.icon.orEmpty() + " " + evt.tool.orEmpty() +
                            (evt.summary?.let { ": $it" } ?: "")).trim(),
                    )
                    "ai_done" -> finishTurn(
                        evt.turnId, evt.ok == true,
                        evt.durationS, evt.error, evt.sessionId,
                    )
                    "ai_state" -> updateAiState(
                        busy = evt.busy == true,
                        sessionId = evt.sessionId,
                        model = evt.model,
                        turnId = evt.turnId,
                    )
                }
            }
        }
    }
}
