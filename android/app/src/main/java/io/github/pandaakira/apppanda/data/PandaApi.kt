package io.github.pandaakira.apppanda.data

import io.github.pandaakira.apppanda.data.models.ActionResult
import io.github.pandaakira.apppanda.data.models.AppsResponse
import io.github.pandaakira.apppanda.data.models.AudioResponse
import io.github.pandaakira.apppanda.data.models.DiskResponse
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
import io.github.pandaakira.apppanda.data.models.UpdatesResponse
import io.github.pandaakira.apppanda.data.models.VersionResponse
import io.github.pandaakira.apppanda.data.models.VpsListResponse
import io.github.pandaakira.apppanda.data.models.VpsSummary
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType.Application
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

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
            requestTimeoutMillis = 15_000
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

    fun close() = client.close()

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

    suspend fun screens(): ScreensResponse =
        client.get(url("/api/v1/screens")).body()

    suspend fun mediaPlayers(): MediaPlayersResponse =
        client.get(url("/api/v1/media/players")).body()

    suspend fun mediaStatus(player: String): MediaStatus =
        client.get(url("/api/v1/media/$player/status")).body()

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

    suspend fun setScreen(output: String, on: Boolean) =
        action("/api/v1/screens/$output/${if (on) "on" else "off"}")

    suspend fun setDpms(on: Boolean) =
        action("/api/v1/screens/dpms/${if (on) "on" else "off"}")

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
    fun events(): Flow<SseEvent> = flow {
        val response: HttpResponse = client.get(url("/api/v1/events")) {
            accept(ContentType("text", "event-stream"))
        }
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
            } catch (_: Exception) {
                // payload inválido, lo ignoramos
            }
        }

        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
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

@Suppress("unused")
private fun HttpRequestBuilder.dummy() {}
