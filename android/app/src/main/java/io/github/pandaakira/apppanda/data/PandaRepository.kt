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

/** Estado del chat IA. Mensajes inmutables + @Serializable para que
 *  PandaRepository pueda persistirlos en DataStore y sobreviva a
 *  cierres de app / kills por OOM. La conversación real vive en Claude
 *  Code via session_id; cuando el session_id observado cambia (Nuevo,
 *  daemon reinstalado sin state), se limpia el cache local. */
@kotlinx.serialization.Serializable
enum class AIRole { User, Assistant }

@kotlinx.serialization.Serializable
data class AIMessage(
    val id: String,
    val role: AIRole,
    val content: String,
    val tools: List<String> = emptyList(),
    val done: Boolean = false,
    val durationS: Double? = null,
    val error: String? = null,
)

@kotlinx.serialization.Serializable
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
            val last = st.messages.lastOrNull()
            if (last != null && last.role == AIRole.Assistant && !last.done) {
                val updated = last.copy(content = last.content + delta)
                st.copy(messages = st.messages.dropLast(1) + updated)
            } else {
                val newMsg = AIMessage(
                    id = turnId ?: System.currentTimeMillis().toString(),
                    role = AIRole.Assistant,
                    content = delta,
                )
                st.copy(messages = st.messages + newMsg)
            }
        }
        scheduleSave()
    }

    private fun appendTool(turnId: String?, line: String) {
        if (line.isBlank()) return
        _aiChat.update { st ->
            val last = st.messages.lastOrNull()
            if (last != null && last.role == AIRole.Assistant && !last.done) {
                val updated = last.copy(tools = last.tools + line)
                st.copy(messages = st.messages.dropLast(1) + updated)
            } else {
                val newMsg = AIMessage(
                    id = turnId ?: System.currentTimeMillis().toString(),
                    role = AIRole.Assistant,
                    content = "",
                    tools = listOf(line),
                )
                st.copy(messages = st.messages + newMsg)
            }
        }
        scheduleSave()
    }

    private fun finishTurn(
        turnId: String?, ok: Boolean,
        durationS: Double?, error: String?, sessionId: String?,
    ) {
        _aiChat.update { st ->
            val last = st.messages.lastOrNull()
            val newMessages = if (!ok && (last == null || last.role != AIRole.Assistant || last.done)) {
                // hubo error y nunca emitimos texto — placeholder
                st.messages + AIMessage(
                    id = turnId ?: System.currentTimeMillis().toString(),
                    role = AIRole.Assistant,
                    content = "",
                    done = true,
                    error = error ?: "error desconocido",
                )
            } else if (last != null && last.role == AIRole.Assistant && !last.done) {
                st.messages.dropLast(1) + last.copy(
                    done = true,
                    durationS = durationS,
                    error = if (!ok) error else null,
                )
            } else {
                st.messages
            }
            st.copy(
                messages = newMessages,
                busy = false,
                currentTurnId = null,
                sessionId = sessionId ?: st.sessionId,
                lastError = if (!ok) error else null,
            )
        }
        scheduleSave()
    }

    private fun updateAiState(
        busy: Boolean, sessionId: String?, model: String?, turnId: String?,
    ) {
        // Si el backend reporta un session_id distinto al que tenemos en
        // cache local, significa que arrancó conversación nueva (reset
        // explícito o daemon perdió su state). Tirar los mensajes viejos.
        _aiChat.update { st ->
            val newSession = sessionId ?: st.sessionId
            val sessionChanged = sessionId != null && st.sessionId != null &&
                sessionId != st.sessionId
            val newMessages = if (sessionChanged) emptyList() else st.messages
            st.copy(
                messages = newMessages,
                busy = busy,
                sessionId = newSession,
                model = model ?: st.model,
                currentTurnId = turnId ?: st.currentTurnId.takeIf { busy },
            )
        }
        scheduleSave()
    }

    fun aiAddUserMessage(content: String, turnId: String?) {
        if (content.isBlank()) return
        _aiChat.update { st ->
            val newMsg = AIMessage(
                id = turnId ?: System.currentTimeMillis().toString(),
                role = AIRole.User,
                content = content,
                done = true,
            )
            st.copy(messages = st.messages + newMsg, busy = true, currentTurnId = turnId)
        }
        scheduleSave()
    }

    fun aiClearMessages() {
        _aiChat.update { it.copy(messages = emptyList(), lastError = null) }
        scheduleSave()
    }

    fun aiSetLastError(error: String) {
        _aiChat.update { it.copy(lastError = error) }
    }

    fun aiSyncFromState(
        busy: Boolean, sessionId: String?, model: String, turnId: String?,
    ) {
        // Misma lógica que updateAiState: si el session_id viene distinto
        // al guardado en cache, asumimos conversación nueva y limpiamos.
        _aiChat.update { st ->
            val newSession = sessionId ?: st.sessionId
            val sessionChanged = sessionId != null && st.sessionId != null &&
                sessionId != st.sessionId
            val newMessages = if (sessionChanged) emptyList() else st.messages
            st.copy(
                messages = newMessages,
                busy = busy,
                sessionId = newSession,
                model = model,
                currentTurnId = turnId,
            )
        }
        scheduleSave()
    }

    // ─── Persistencia del chat IA en DataStore ───────────────────────────

    private val aiJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Debounce de saves: hay mucho churn por ai_chunk (decenas de deltas
     *  por respuesta). Coalescemos en un único save 500ms después del
     *  último cambio. */
    @Volatile
    private var pendingSave: kotlinx.coroutines.Job? = null

    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave = scope.launch(Dispatchers.IO) {
            delay(500)
            try {
                // Estado actual al momento del save (no el de cuando se programó)
                val st = _aiChat.value
                // No persistimos transient: busy/currentTurnId/lastError
                val toSave = st.copy(
                    busy = false,
                    currentTurnId = null,
                    lastError = null,
                )
                val json = aiJson.encodeToString(AIChatState.serializer(), toSave)
                settings.saveAiChat(json)
            } catch (_: Exception) {
                // si serialización falla, seguimos sin persistir este snapshot
            }
        }
    }

    private suspend fun loadAiChatFromDisk() {
        val raw = settings.aiChat.first()
        if (raw.isBlank()) return
        try {
            val restored = aiJson.decodeFromString(AIChatState.serializer(), raw)
            _aiChat.value = restored.copy(
                busy = false,
                currentTurnId = null,
                lastError = null,
            )
        } catch (_: Exception) {
            // formato viejo / corrupto — empezar limpio
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
        // 1) Hidratar el chat desde DataStore antes de empezar a consumir
        //    eventos, así no perdemos el primer ai_state que llega del
        //    backend al reconectar.
        scope.launch(Dispatchers.IO) {
            loadAiChatFromDisk()
        }
        // 2) Colectar eventos SSE del módulo IA.
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
