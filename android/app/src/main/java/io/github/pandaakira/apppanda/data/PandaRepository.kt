package io.github.pandaakira.apppanda.data

import io.github.pandaakira.apppanda.data.models.SseEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pending sudo approval request — vive en memoria, no se persiste.
 *  Guarda el backend del PC que la emitió: la decisión se manda a ESE PC
 *  aunque el perfil activo sea otro. */
data class SudoPending(
    val rid: String,
    val prompt: String,
    val command: String,
    val timeoutS: Int,
    val receivedAtMs: Long,
    val profileId: String,
    val pcName: String,
    val baseUrl: String,
    val token: String,
) {
    /** Identifica la solicitud entre todos los PCs (el rid es solo por PC). */
    val key: String get() = "$profileId/$rid"
}

/** Evento SSE junto con el PC que lo emitió. */
private data class ProfileEvent(val profile: Profile, val evt: SseEvent)

/** Singleton liviano que mantiene el API del PC activo y los streams SSE de
 *  todos los PCs configurados. */
class PandaRepository(
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _api = MutableStateFlow<PandaApi?>(null)
    val api: StateFlow<PandaApi?> = _api.asStateFlow()

    /** Id del perfil activo ("" en una instalación legacy sin perfiles). */
    private val _activeProfileId = MutableStateFlow<String?>(null)

    /** Timestamp (epoch ms) del último evento SSE recibido del PC activo.
     *  Permite a la UI mostrar "conectado" si fue hace menos de N segundos. */
    private val _lastEventAt = MutableStateFlow<Long>(0)
    val lastEventAt: StateFlow<Long> = _lastEventAt.asStateFlow()

    /** Última vez que se leyó CUALQUIER línea del stream SSE del PC activo
     *  (incluido heartbeat). Si esto avanza pero lastEventAt queda, el parser
     *  falla. */
    private val _lastByteAt = MutableStateFlow<Long>(0)
    val lastByteAt: StateFlow<Long> = _lastByteAt.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _parseErrorCount = MutableStateFlow(0)
    val parseErrorCount: StateFlow<Int> = _parseErrorCount.asStateFlow()

    /** Solicitudes de sudo pendientes de CUALQUIER PC, en orden de llegada.
     *  La UI muestra un dialog modal para la primera. AlertsService las
     *  agrega cuando llega un sudo_request; el dialog las quita al decidir o
     *  al vencer el timeout. Es una cola para que dos PCs pidiendo a la vez
     *  no se pisen. */
    private val _pendingSudo = MutableStateFlow<List<SudoPending>>(emptyList())
    val pendingSudo: StateFlow<List<SudoPending>> = _pendingSudo.asStateFlow()

    /** Encola la solicitud. Devuelve false si ya estaba: el backend reenvía
     *  las pendientes cada vez que el SSE se reconecta. */
    fun addPendingSudo(req: SudoPending): Boolean {
        var added = false
        _pendingSudo.update { list ->
            added = list.none { it.key == req.key }
            if (added) list + req else list
        }
        return added
    }

    fun clearPendingSudo(key: String) {
        _pendingSudo.update { list -> list.filterNot { it.key == key } }
    }

    /** PCs a escuchar. Sin perfiles (instalación legacy aún sin migrar) cae
     *  al backend suelto como un PC con id "", el mismo que devuelve
     *  Settings.activeProfileId en ese caso. */
    private val watchedProfiles: Flow<List<Profile>> =
        combine(settings.profiles, settings.config) { profiles, cfg ->
            when {
                profiles.isNotEmpty() -> profiles.filter { it.isConfigured }
                cfg.isConfigured -> listOf(Profile("", "PC", cfg.baseUrl, cfg.token))
                else -> emptyList()
            }
        }.distinctUntilChanged()

    /** Source flow: una conexión SSE por PC, compartida entre todos los
     *  colectores (HomeScreen + AlertsService). Si nadie escucha por 5s, las
     *  conexiones se cierran; cuando alguien vuelve a escuchar, se reabren.
     *
     *  Antes solo había conexión con el PC activo, así que las solicitudes
     *  sudo de los otros PCs no llegaban hasta cambiar de perfil. Ahora se
     *  escuchan todos a la vez y cambiar de perfil no reconecta nada: solo
     *  cambia qué eventos pasan por [events]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val allEvents: SharedFlow<ProfileEvent> = watchedProfiles.flatMapLatest { profiles ->
        if (profiles.isEmpty()) {
            emptyFlow()
        } else channelFlow {
            for (p in profiles) launch { streamProfile(p) }
        }
    }.shareIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        replay = 0,
    )

    /** Eventos del PC activo (métricas en vivo, alertas, servicios…). */
    val events: Flow<SseEvent> = allEvents
        .filter { it.profile.id == _activeProfileId.value }
        .map { it.evt }

    /** Solicitudes sudo de TODOS los PCs, sin importar cuál está activo. */
    val sudoRequests: Flow<SudoPending> = allEvents
        .filter { it.evt.type == "sudo_request" && !it.evt.rid.isNullOrBlank() }
        .map { (p, evt) ->
            SudoPending(
                rid = evt.rid.orEmpty(),
                prompt = evt.prompt.orEmpty(),
                command = evt.command.orEmpty(),
                timeoutS = evt.timeoutS ?: 60,
                receivedAtMs = System.currentTimeMillis(),
                profileId = p.id,
                pcName = p.name.ifBlank { evt.hostname ?: "PC" },
                baseUrl = p.baseUrl,
                token = p.token,
            )
        }

    /** Mantiene abierto el SSE de un PC, reconectando con backoff. Solo el
     *  PC activo actualiza el estado de conexión que muestra la UI. */
    private suspend fun ProducerScope<ProfileEvent>.streamProfile(p: Profile) {
        val api = PandaApi(p.baseUrl, p.token)
        var backoffMs = 1_000L
        fun isActiveProfile() = p.id == _activeProfileId.value
        try {
            while (true) {
                try {
                    api.events(onByte = {
                        if (isActiveProfile()) _lastByteAt.value = System.currentTimeMillis()
                    }).collect { evt ->
                        if (evt.type == "__parse_error__") {
                            if (isActiveProfile()) {
                                _parseErrorCount.value = _parseErrorCount.value + 1
                                _lastError.value = "parse: ${evt.title}"
                            }
                            return@collect
                        }
                        backoffMs = 1_000
                        if (isActiveProfile()) {
                            _lastEventAt.value = System.currentTimeMillis()
                            _lastError.value = null
                        }
                        if (evt.type != "hello") send(ProfileEvent(p, evt))
                    }
                } catch (e: CancellationException) {
                    // Cambió la lista de PCs o se cerró el flow: propagar, no tragar.
                    throw e
                } catch (e: Exception) {
                    if (isActiveProfile()) {
                        _lastError.value = "conn: ${e.message?.take(80) ?: e::class.simpleName}"
                    }
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000)
            }
        } finally {
            api.close()
        }
    }

    // Init al final: las propiedades de arriba ya están inicializadas cuando
    // arrancan los colectores.
    init {
        scope.launch(Dispatchers.IO) {
            // distinctUntilChanged: reconstruir el API solo cuando cambia el
            // backend (baseUrl/token), no ante cada escritura de settings (tema,
            // push, etc.). Cambiar de perfil a otro PC sí lo dispara.
            settings.config.distinctUntilChanged().collect { cfg ->
                _api.value?.close()
                _api.value = if (cfg.isConfigured) PandaApi(cfg.baseUrl, cfg.token) else null
            }
        }
        scope.launch {
            settings.activeProfileId.distinctUntilChanged().collect { id ->
                _activeProfileId.value = id
                // El estado de conexión es del PC activo: al cambiar, empieza
                // de cero hasta que llegue el primer evento del nuevo.
                _lastEventAt.value = 0
                _lastByteAt.value = 0
                _lastError.value = null
            }
        }
    }
}
