package io.github.pandaakira.apppanda.data

import io.github.pandaakira.apppanda.data.models.ActionResult
import io.github.pandaakira.apppanda.data.models.AppsResponse
import io.github.pandaakira.apppanda.data.models.AudioResponse
import io.github.pandaakira.apppanda.data.models.ClipboardResponse
import io.github.pandaakira.apppanda.data.models.InhibitResponse
import io.github.pandaakira.apppanda.data.models.RunningGameResponse
import io.github.pandaakira.apppanda.data.models.ScenesResponse
import io.github.pandaakira.apppanda.data.models.TerminalResponse
import io.github.pandaakira.apppanda.data.models.DiskResponse
import io.github.pandaakira.apppanda.data.models.FileUploadResponse
import io.github.pandaakira.apppanda.data.models.FilesDeleteReq
import io.github.pandaakira.apppanda.data.models.FilesIndexResponse
import io.github.pandaakira.apppanda.data.models.FilesListResponse
import io.github.pandaakira.apppanda.data.models.FilesMkdirReq
import io.github.pandaakira.apppanda.data.models.FilesOpReq
import io.github.pandaakira.apppanda.data.models.FilesRenameReq
import io.github.pandaakira.apppanda.data.models.GamesResponse
import io.github.pandaakira.apppanda.data.models.GpusResponse
import io.github.pandaakira.apppanda.data.models.HealthResponse
import io.github.pandaakira.apppanda.data.models.LogsResponse
import io.github.pandaakira.apppanda.data.models.MediaPlayersResponse
import io.github.pandaakira.apppanda.data.models.MediaStatus
import io.github.pandaakira.apppanda.data.models.MetricsResponse
import io.github.pandaakira.apppanda.data.models.NetNeighborsResponse
import io.github.pandaakira.apppanda.data.models.NetStatus
import io.github.pandaakira.apppanda.data.models.ProcessesResponse
import io.github.pandaakira.apppanda.data.models.ScreensResponse
import io.github.pandaakira.apppanda.data.models.ServicesResponse
import io.github.pandaakira.apppanda.data.models.SseEvent
import io.github.pandaakira.apppanda.data.models.SystemStatus
import io.github.pandaakira.apppanda.data.models.TempsResponse
import io.github.pandaakira.apppanda.data.models.ThemesResponse
import io.github.pandaakira.apppanda.data.models.UpdatesResponse
import io.github.pandaakira.apppanda.data.models.VersionResponse
import io.github.pandaakira.apppanda.data.models.VpsListResponse
import io.github.pandaakira.apppanda.data.models.VpsSummary
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType.Application
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/** Fuente de deltas de mouse para [PandaApi.mouseStream]. */
fun interface MouseDeltaSource {
    /** Parte entera del movimiento acumulado; conserva la fracción sub-pixel. */
    fun take(): Pair<Int, Int>
}

private const val STREAM_TICK_MS = 12L
// A 12 ms/tick, ~14 s sin movimiento → keepalive.
private const val STREAM_KEEPALIVE_TICKS = 1200

class PandaApi(
    private val baseUrl: String,
    private val token: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 60_000
        }
        install(ContentNegotiation) {
            json(json)
        }
        install(DefaultRequest) {
            if (token.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            accept(ContentType.Application.Json)
        }
    }

    // Cliente dedicado para SSE: usa engine OkHttp (mas robusto que
    // CIO para HTTP/1.1 chunked encoding — CIO 3.0.2 tira "Chunked
    // stream has ended unexpectedly" al parsear streams largos).
    // Sin ContentNegotiation y sin socketTimeout.
    private val sseClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
        }
        install(DefaultRequest) {
            if (token.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        engine {
            // OkHttp: timeouts del engine, no del plugin HttpTimeout
            config {
                retryOnConnectionFailure(true)
                connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    fun close() {
        client.close()
        sseClient.close()
    }

    private fun url(path: String) = "$baseUrl$path"

    suspend fun health(): HealthResponse = client.get(url("/api/v1/health")).body()
    suspend fun version(): VersionResponse = client.get(url("/api/v1/version")).body()
    suspend fun systemStatus(): SystemStatus = client.get(url("/api/v1/status/system")).body()
    suspend fun disk(): DiskResponse = client.get(url("/api/v1/status/disk")).body()
    suspend fun net(): NetStatus = client.get(url("/api/v1/status/net")).body()
    suspend fun temps(): TempsResponse = client.get(url("/api/v1/status/temps")).body()
    suspend fun gpu(): GpusResponse = client.get(url("/api/v1/status/gpu")).body()
    suspend fun processes(sort: String = "cpu", limit: Int = 10): ProcessesResponse =
        client.get(url("/api/v1/processes?sort=$sort&limit=$limit")).body()
    suspend fun services(): ServicesResponse = client.get(url("/api/v1/services")).body()
    suspend fun metrics(range: String = "1h"): MetricsResponse =
        client.get(url("/api/v1/metrics?range=$range")).body()

    suspend fun logs(priority: String = "err", n: Int = 30): LogsResponse =
        client.get(url("/api/v1/logs?priority=$priority&n=$n")).body()

    suspend fun updates(): UpdatesResponse =
        client.get(url("/api/v1/updates")).body()

    suspend fun audio(): AudioResponse =
        client.get(url("/api/v1/audio/sinks")).body()

    suspend fun clipboardGet(): ClipboardResponse =
        client.get(url("/api/v1/clipboard")).body()

    suspend fun scenes(): ScenesResponse =
        client.get(url("/api/v1/scenes")).body()

    suspend fun runningGame(): RunningGameResponse =
        client.get(url("/api/v1/games/running")).body()

    suspend fun inhibitState(): InhibitResponse =
        client.get(url("/api/v1/inhibit")).body()

    suspend fun screens(): ScreensResponse =
        client.get(url("/api/v1/screens")).body()

    suspend fun mediaPlayers(): MediaPlayersResponse =
        client.get(url("/api/v1/media/players")).body()

    suspend fun mediaStatus(player: String): MediaStatus =
        client.get(url("/api/v1/media/$player/status")).body()

    // ─── Control de mouse y teclado (ydotool / wtype) ──────────────────────
    suspend fun mouseMove(dx: Int, dy: Int) =
        action("/api/v1/input/mouse/move", body = mapOf("dx" to dx, "dy" to dy))

    /**
     * Stream de movimiento de mouse: abre UNA conexión POST de larga duración
     * y va escribiendo deltas `dx,dy\n` a cadencia fija, sin esperar ack por
     * cada uno. Así la frecuencia es regular (no depende del RTT) y se siente
     * suave. La función suspende hasta que la corrutina se cancela (al salir
     * del módulo / mandar la app a background) — ahí cierra la conexión.
     *
     * `source.take()` devuelve el delta entero acumulado (conservando la
     * fracción sub-pixel). Cuando no hay movimiento por un rato, manda un
     * keepalive `0,0` para que el socket no muera por inactividad.
     */
    suspend fun mouseStream(source: MouseDeltaSource) {
        client.post(url("/api/v1/input/mouse/stream")) {
            // Stream largo: sin tope de socket para esta request puntual.
            timeout { socketTimeoutMillis = Long.MAX_VALUE }
            setBody(object : OutgoingContent.WriteChannelContent() {
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    var idleTicks = 0
                    while (true) {
                        delay(STREAM_TICK_MS)
                        val (dx, dy) = source.take()
                        if (dx != 0 || dy != 0) {
                            channel.writeStringUtf8("$dx,$dy\n")
                            channel.flush()
                            idleTicks = 0
                        } else if (++idleTicks >= STREAM_KEEPALIVE_TICKS) {
                            channel.writeStringUtf8("0,0\n")
                            channel.flush()
                            idleTicks = 0
                        }
                    }
                }
            })
        }
    }

    suspend fun mouseClick(button: String) =
        action("/api/v1/input/mouse/click", body = mapOf("button" to button))

    suspend fun mouseScroll(direction: String) =
        action("/api/v1/input/mouse/scroll", body = mapOf("direction" to direction))

    /** Agranda el cursor del PC unos segundos para ubicarlo (p. ej. en la tele). */
    suspend fun cursorHighlight() =
        action("/api/v1/input/cursor/highlight")

    suspend fun keyPress(key: String) =
        action("/api/v1/input/key", body = mapOf("key" to key))

    suspend fun typeText(text: String) =
        action("/api/v1/input/type", body = mapOf("text" to text))

    suspend fun netNeighbors(): NetNeighborsResponse =
        client.get(url("/api/v1/net/neighbors")).body()

    suspend fun vpsList(): VpsListResponse =
        client.get(url("/api/v1/vps")).body()

    suspend fun vpsSummary(alias: String): VpsSummary =
        client.get(url("/api/v1/vps/$alias/summary")).body()

    suspend fun games(): GamesResponse =
        client.get(url("/api/v1/games")).body()

    suspend fun apps(): AppsResponse =
        client.get(url("/api/v1/apps")).body()

    suspend fun themes(): ThemesResponse =
        client.get(url("/api/v1/themes")).body()

    /** Baja los bytes de una imagen de fondo de tema desde la carpeta del PC. */
    suspend fun themeImage(name: String): ByteArray {
        val encoded = java.net.URLEncoder.encode(name, "UTF-8")
        return client.get(url("/api/v1/themes/image?name=$encoded")).body()
    }

    // ─── Files ────────────────────────────────────────────────────────────

    suspend fun filesIndex(): FilesIndexResponse =
        client.get(url("/api/v1/files")).body()

    suspend fun filesList(dirIdx: Int, rel: String = ""): FilesListResponse {
        val r = java.net.URLEncoder.encode(rel, "UTF-8")
        return client.get(url("/api/v1/files?dir=$dirIdx&rel=$r")).body()
    }

    /** Devuelve el HttpResponse para que el caller streamee el body al
     *  ContentResolver del MediaStore (no carga el archivo en memoria). */
    suspend fun filesDownload(dirIdx: Int, rel: String, name: String): HttpResponse {
        val r = java.net.URLEncoder.encode(rel, "UTF-8")
        val encoded = java.net.URLEncoder.encode(name, "UTF-8")
        return client.get(url("/api/v1/files/download?dir=$dirIdx&rel=$r&name=$encoded"))
    }

    /** Sube bytes con header X-Filename y Content-Length para que el daemon
     *  los stree al disco. X-Dir/X-Rel eligen la carpeta destino. */
    suspend fun filesUpload(
        name: String, bytes: ByteArray, dirIdx: Int = 0, rel: String = "",
    ): FileUploadResponse {
        val encoded = java.net.URLEncoder.encode(name, "UTF-8")
        val r = java.net.URLEncoder.encode(rel, "UTF-8")
        return client.post(url("/api/v1/files/upload")) {
            header("X-Filename", encoded)
            header("X-Dir", dirIdx.toString())
            header("X-Rel", r)
            header(HttpHeaders.ContentLength, bytes.size.toString())
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }.body()
    }

    suspend fun filesMkdir(dirIdx: Int, rel: String, name: String) =
        action("/api/v1/files/mkdir", body = FilesMkdirReq(dirIdx, rel, name))

    suspend fun filesRename(dirIdx: Int, rel: String, name: String, newName: String) =
        action("/api/v1/files/rename", body = FilesRenameReq(dirIdx, rel, name, newName))

    suspend fun filesDelete(dirIdx: Int, rel: String, name: String, recursive: Boolean) =
        action("/api/v1/files/delete", confirm = true,
            body = FilesDeleteReq(dirIdx, rel, name, recursive))

    suspend fun filesOpen(dirIdx: Int, rel: String, name: String) =
        action("/api/v1/files/open", body = FilesOpReq(dirIdx, rel, name))

    // ─── POST (acciones) ──────────────────────────────────────────────────

    private suspend fun action(
        path: String, confirm: Boolean = false, body: Any? = null,
    ): ActionResult = client.post(url(path)) {
        if (confirm) header("X-Confirm", "true")
        if (body != null) {
            contentType(Application.Json)
            setBody(body)
        }
    }.body()

    suspend fun powerAction(action: String) =
        action("/api/v1/power/$action", confirm = true)

    suspend fun killProcess(pid: String) =
        action("/api/v1/processes/$pid/kill", confirm = true)

    suspend fun serviceAction(unit: String, act: String) =
        action("/api/v1/services/$unit/$act", confirm = true)

    suspend fun applyUpdates() =
        action("/api/v1/updates/apply", confirm = true)

    suspend fun setAudioSink(sink: String) =
        action("/api/v1/audio/sink", body = mapOf("sink" to sink))

    suspend fun setVolume(pct: Int) =
        action("/api/v1/audio/volume", body = mapOf("pct" to pct))

    /** state: "on" | "off" | "toggle". */
    suspend fun setMute(state: String) =
        action("/api/v1/audio/mute", body = mapOf("state" to state))

    suspend fun setAppVolume(id: Int, pct: Int) =
        action("/api/v1/audio/app/$id/volume", body = mapOf("pct" to pct))

    suspend fun setAppMute(id: Int, state: String) =
        action("/api/v1/audio/app/$id/mute", body = mapOf("state" to state))

    suspend fun setMicMute(state: String) =
        action("/api/v1/audio/mic/mute", body = mapOf("state" to state))

    suspend fun setMicVolume(pct: Int) =
        action("/api/v1/audio/mic/volume", body = mapOf("pct" to pct))

    suspend fun applyScene(name: String) =
        action("/api/v1/scenes/$name/apply")

    suspend fun closeGame() =
        action("/api/v1/games/close", confirm = true)

    suspend fun setInhibit(on: Boolean) =
        action("/api/v1/inhibit/${if (on) "on" else "off"}")

    suspend fun terminalRun(cmd: String): TerminalResponse =
        client.post(url("/api/v1/terminal/run")) {
            contentType(Application.Json)
            setBody(mapOf("cmd" to cmd))
        }.body()

    suspend fun clipboardSet(text: String) =
        action("/api/v1/clipboard", body = mapOf("text" to text))

    suspend fun setScreen(output: String, on: Boolean) =
        action("/api/v1/screens/$output/${if (on) "on" else "off"}")

    suspend fun setDpms(on: Boolean) =
        action("/api/v1/screens/dpms/${if (on) "on" else "off"}")

    suspend fun niriCmd(cmd: String, output: String? = null) =
        action(
            "/api/v1/niri/cmd/$cmd" +
                if (output != null)
                    "?output=${java.net.URLEncoder.encode(output, "UTF-8")}"
                else "",
        )

    suspend fun mediaAction(player: String, act: String) =
        action("/api/v1/media/$player/$act")

    suspend fun launchApp(name: String) =
        action("/api/v1/apps/$name/launch")

    suspend fun launchGame(appid: String) =
        action("/api/v1/games/$appid/launch")

    suspend fun wakeOnLan(alias: String) =
        action("/api/v1/net/wake/$alias")

    suspend fun sudoDecision(rid: String, approved: Boolean) =
        action("/api/v1/sudo/$rid/decision", body = mapOf("approved" to approved))

    /**
     * SSE stream. Lee líneas crudas del response, parsea `event:` y `data:`,
     * y emite cada evento deserializado. Si la conexión se cierra, el Flow
     * termina; el caller decide si reintentar.
     *
     * Implementación manual sobre ByteReadChannel para no depender del
     * plugin de SSE de ktor (que añade restricciones de versión).
     */
    /**
     * SSE stream. Si pasás `onByte`, se invoca con cada línea leída del
     * stream (raw, incluido heartbeat) — útil para que el repo trackee
     * cuándo llegó el último byte aunque el parse falle.
     */
    fun events(onByte: (() -> Unit)? = null): Flow<SseEvent> = flow {
        // prepareGet + execute mantiene la response viva durante todo
        // el bloque. Con `client.get` Ktor cierra la response al salir
        // del scope (que en streams largos == inmediatamente), tirando
        // excepciones de tipo "request body length should be specified"
        // u otras al intentar leer el channel.
        sseClient.prepareGet(url("/api/v1/events")) {
            accept(ContentType("text", "event-stream"))
        }.execute { response ->
            val channel = response.bodyAsChannel()

            var currentEvent: String? = null
            val dataBuf = StringBuilder()

            suspend fun emitIfReady() {
                if (dataBuf.isEmpty()) {
                    currentEvent = null
                    return
                }
                val payload = dataBuf.toString()
                dataBuf.clear()
                currentEvent = null
                try {
                    val evt = json.decodeFromString(SseEvent.serializer(), payload)
                    emit(evt)
                } catch (e: Exception) {
                    emit(SseEvent(type = "__parse_error__", title = e.message?.take(80)))
                }
            }

            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                onByte?.invoke()
                when {
                    line.isEmpty() -> emitIfReady()
                    line.startsWith(":") -> { /* comment / heartbeat */ }
                    line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                        dataBuf.append(line.removePrefix("data:").trimStart())
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private fun HttpRequestBuilder.dummy() {}
