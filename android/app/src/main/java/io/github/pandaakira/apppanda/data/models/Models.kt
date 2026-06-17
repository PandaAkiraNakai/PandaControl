package io.github.pandaakira.apppanda.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class HealthResponse(val ok: Boolean, val version: String)

@Serializable
data class VersionResponse(
    val version: String,
    val hostname: String,
    val kernel: String,
    @SerialName("boot_id") val bootId: String,
)

@Serializable
data class CpuInfo(val pct: Double, val cores: Int)

@Serializable
data class LoadAvg(
    @SerialName("1m") val m1: Double,
    @SerialName("5m") val m5: Double,
    @SerialName("15m") val m15: Double,
)

@Serializable
data class RamInfo(
    @SerialName("total_g") val totalG: Double,
    @SerialName("used_g") val usedG: Double,
    val pct: Double,
)

@Serializable
data class SystemStatus(
    val hostname: String,
    val kernel: String,
    @SerialName("uptime_s") val uptimeS: Double,
    @SerialName("boot_id") val bootId: String,
    val cpu: CpuInfo,
    val load: LoadAvg,
    val ram: RamInfo,
)

@Serializable
data class DiskMount(
    val source: String,
    val mount: String,
    @SerialName("size_g") val sizeG: Double,
    @SerialName("used_g") val usedG: Double,
    val pct: Double,
)

@Serializable
data class DiskResponse(val mounts: List<DiskMount>)

@Serializable
data class TempEntry(val chip: String, val label: String, val c: Double)

@Serializable
data class TempsResponse(val temps: List<TempEntry>)

@Serializable
data class GpuEntry(
    val name: String,
    val vendor: String,
    val pct: Double? = null,
    @SerialName("mem_pct") val memPct: Double? = null,
    @SerialName("mem_used_g") val memUsedG: Double? = null,
    @SerialName("mem_total_g") val memTotalG: Double? = null,
    @SerialName("temp_c") val tempC: Double? = null,
    @SerialName("power_w") val powerW: Double? = null,
    @SerialName("fan_rpm") val fanRpm: Int? = null,
)

@Serializable
data class GpusResponse(val gpus: List<GpuEntry>)

@Serializable
data class ProcessRow(
    val pid: String,
    val user: String,
    @SerialName("cpu_pct") val cpuPct: String,
    @SerialName("ram_pct") val ramPct: String,
    val comm: String,
)

@Serializable
data class ProcessesResponse(val sort: String, val rows: List<ProcessRow>)

@Serializable
data class ServiceWatchRow(val unit: String, val state: String)

@Serializable
data class ServicesResponse(
    val failed: List<String>,
    val watch: List<ServiceWatchRow>,
    val manageable: List<String> = emptyList(),
)

@Serializable
data class MetricRow(
    val ts: Long,
    @SerialName("cpu_pct") val cpuPct: Double? = null,
    @SerialName("ram_pct") val ramPct: Double? = null,
    @SerialName("gpu_pct") val gpuPct: Double? = null,
    @SerialName("cpu_temp") val cpuTemp: Double? = null,
    @SerialName("gpu_temp") val gpuTemp: Double? = null,
    val load1: Double? = null,
    @SerialName("disk_pct") val diskPct: Double? = null,
)

@Serializable
data class MetricsResponse(
    val range: String,
    @SerialName("range_s") val rangeS: Long,
    val rows: List<MetricRow>,
)

@Serializable
data class NetInterface(
    @SerialName("rx_bps") val rxBps: Double = 0.0,
    @SerialName("tx_bps") val txBps: Double = 0.0,
    @SerialName("rx_total") val rxTotal: Long = 0,
    @SerialName("tx_total") val txTotal: Long = 0,
)

@Serializable
data class PingResult(val host: String, val ok: Boolean, @SerialName("rtt_ms") val rttMs: Double? = null)

@Serializable
data class NetStatus(
    val interfaces: Map<String, NetInterface> = emptyMap(),
    val pings: List<PingResult> = emptyList(),
)

@Serializable
data class LogsResponse(val priority: String, val n: Int, val output: String)

@Serializable
data class UpdatePkg(val name: String, val from: String, val to: String)

@Serializable
data class UpdatesResponse(
    val count: Int,
    val packages: List<UpdatePkg>,
    val raw: String = "",
)

@Serializable
data class AudioSink(
    val name: String,
    val label: String,
    val icon: String,
    val kind: String,
)

@Serializable
data class AudioMaster(
    @SerialName("volume_pct") val volumePct: Int? = null,
    val muted: Boolean? = null,
    val sink: String = "",
    val error: String? = null,
)

@Serializable
data class AudioMic(
    @SerialName("volume_pct") val volumePct: Int? = null,
    val muted: Boolean? = null,
    val source: String = "",
    val error: String? = null,
)

@Serializable
data class AudioApp(
    val id: Int,
    val label: String,
    val media: String = "",
    @SerialName("volume_pct") val volumePct: Int? = null,
    val muted: Boolean = false,
)

@Serializable
data class AudioResponse(
    val sinks: List<AudioSink>,
    val default: String = "",
    val master: AudioMaster? = null,
    val mic: AudioMic? = null,
    val apps: List<AudioApp> = emptyList(),
    val error: String? = null,
)

@Serializable
data class Scene(val name: String, val label: String)

@Serializable
data class ScenesResponse(val scenes: List<Scene> = emptyList())

@Serializable
data class RunningGame(val appid: String, val name: String)

@Serializable
data class RunningGameResponse(val running: RunningGame? = null)

@Serializable
data class InhibitResponse(val active: Boolean = false)

@Serializable
data class TerminalResponse(
    val stdout: String = "",
    val stderr: String = "",
    val exit: Int = 0,
    val truncated: Boolean = false,
    val error: String? = null,
)

@Serializable
data class ClipboardResponse(
    val text: String = "",
    val error: String? = null,
)

@Serializable
data class NiriOutput(val name: String, val label: String, val on: Boolean)

@Serializable
data class ScreensResponse(
    val outputs: List<NiriOutput>,
    val error: String? = null,
)

@Serializable
data class MediaPlayersResponse(val players: List<String>)

@Serializable
data class MediaStatus(
    val status: String = "?",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
)

@Serializable
data class NeighborEntry(
    val ip: String,
    val mac: String = "",
    val dev: String = "",
    val state: String = "",
)

@Serializable
data class NetNeighborsResponse(
    val gateway: String = "",
    val neighbors: List<NeighborEntry> = emptyList(),
)

@Serializable
data class VpsHostEntry(
    val alias: String,
    @SerialName("ssh_alias") val sshAlias: String = "",
)

@Serializable
data class VpsListResponse(val hosts: List<VpsHostEntry>)

@Serializable
data class VpsSummary(
    val alias: String,
    val output: String = "",
    val error: String? = null,
)

@Serializable
data class Game(val appid: String, val name: String)

@Serializable
data class GamesResponse(
    @SerialName("use_gamescope") val useGamescope: Boolean = true,
    val games: List<Game>,
)

@Serializable
data class AppEntry(
    val name: String,
    val label: String,
    val cmd: List<String> = emptyList(),
)

@Serializable
data class AppsResponse(val apps: List<AppEntry>)

@Serializable
data class SharedDir(val idx: Int, val path: String, val label: String)

@Serializable
data class FilesIndexResponse(
    val enabled: Boolean = true,
    @SerialName("upload_to") val uploadTo: String = "",
    @SerialName("max_upload_mb") val maxUploadMb: Int = 0,
    val dirs: List<SharedDir> = emptyList(),
)

@Serializable
data class FileEntry(
    val name: String,
    val size: Long,
    val mtime: Long,
    @SerialName("is_dir") val isDir: Boolean = false,
)

@Serializable
data class FilesListResponse(
    val enabled: Boolean = true,
    @SerialName("dir_idx") val dirIdx: Int = 0,
    val rel: String = "",
    val path: String = "",
    val files: List<FileEntry> = emptyList(),
    val error: String? = null,
)

/** Bodies de las acciones de gestión de archivos (kotlinx no serializa
 *  mapas heterogéneos, así que cada acción usa su propio DTO). */
@Serializable
data class FilesOpReq(val dir: Int, val rel: String, val name: String)

@Serializable
data class FilesRenameReq(
    val dir: Int,
    val rel: String,
    val name: String,
    @SerialName("new_name") val newName: String,
)

@Serializable
data class FilesDeleteReq(
    val dir: Int,
    val rel: String,
    val name: String,
    val recursive: Boolean,
)

@Serializable
data class FilesMkdirReq(val dir: Int, val rel: String, val name: String)

@Serializable
data class FileUploadResponse(
    val result: String = "",
    val error: String? = null,
    @SerialName("saved_as") val savedAs: String? = null,
    val path: String? = null,
    val size: Long? = null,
)

@Serializable
data class ThemeColors(
    val background: String,
    val surface: String,
    val surfaceHigh: String,
    val onSurface: String,
    val onSurfaceMuted: String,
    val yellow: String,
    val magenta: String,
    val cyan: String,
    val green: String,
    val red: String,
    val orange: String,
)

@Serializable
data class ThemeDef(
    val id: String,
    val name: String,
    val category: String = "",
    val dark: Boolean = true,
    val font: String = "default",
    val iconStyle: String = "outlined",
    val corner: Int = 12,
    val border: Int = 1,
    val backgroundImage: String = "",
    val backgroundImages: List<String> = emptyList(),
    val backgroundEffect: String = "",
    val colors: ThemeColors,
)

@Serializable
data class ThemesResponse(
    val dir: String = "",
    val themes: List<ThemeDef> = emptyList(),
)

@Serializable
data class ActionResult(
    val result: String = "",
    val error: String? = null,
    // campos opcionales por endpoint (no todos vienen siempre):
    val action: String? = null,
    val unit: String? = null,
    val pid: String? = null,
    val sink: String? = null,
    val output: String? = null,
    val player: String? = null,
    val app: String? = null,
    val appid: String? = null,
    val alias: String? = null,
    val chars: Int? = null,
) {
    val ok: Boolean get() = result.lowercase() in setOf("ok", "started")
}

/** Eventos del stream SSE — algunos campos llegan según el `type`. */
@Serializable
data class SseEvent(
    val type: String,
    val ts: Long = 0,
    val version: String? = null,
    @SerialName("boot_id") val bootId: String? = null,
    // metric_tick
    @SerialName("cpu_pct") val cpuPct: Double? = null,
    @SerialName("ram_pct") val ramPct: Double? = null,
    @SerialName("gpu_pct") val gpuPct: Double? = null,
    @SerialName("cpu_temp") val cpuTemp: Double? = null,
    @SerialName("gpu_temp") val gpuTemp: Double? = null,
    val load1: Double? = null,
    @SerialName("disk_pct") val diskPct: Double? = null,
    val live: Boolean? = null,
    // service_failed
    val unit: String? = null,
    // session_new
    val session: String? = null,
    // boot
    val hostname: String? = null,
    @SerialName("uptime_s") val uptimeS: Double? = null,
    val quote: String? = null,
    // resume
    @SerialName("gap_s") val gapS: Double? = null,
    // alert (legacy from bot)
    val key: String? = null,
    val value: Double? = null,
    val title: String? = null,
    @SerialName("value_str") val valueStr: String? = null,
    val snap: JsonElement? = null,
    // sudo_request
    val rid: String? = null,
    val prompt: String? = null,
    val command: String? = null,
    @SerialName("timeout_s") val timeoutS: Int? = null,
)

// ─── Memorias (visor de solo lectura de la base del CLI `mem`) ───────────────

@Serializable
data class MemoriaRow(
    val id: Int,
    val tipo: String,
    val nombre: String,
    val resumen: String,
    val contenido: String,
    val tags: String = "",
    val activo: Int = 1,
    val creado: String,
    val actualizado: String,
    val dispositivo: String = "compartida",
)

@Serializable
data class MemoriasResponse(
    val enabled: Boolean = true,
    val total: Int = 0,
    val error: String? = null,
    val memorias: List<MemoriaRow> = emptyList(),
)

@Serializable
data class PedidoRow(
    val id: Int,
    val titulo: String,
    val detalle: String = "",
    val estado: String,
    val prioridad: String,
    val vence: String = "",
    val notas: String = "",
    val creado: String,
    val actualizado: String,
    val completado: String = "",
)

@Serializable
data class PedidosResponse(
    val total: Int = 0,
    val error: String? = null,
    val pedidos: List<PedidoRow> = emptyList(),
)

// ─── Docker (contenedores) ───────────────────────────────────────────────────

@Serializable
data class DockerContainer(
    val id: String = "",
    val name: String,
    val image: String = "",
    val state: String = "",
    val status: String = "",
    val ports: String = "",
)

@Serializable
data class DockerResponse(
    val enabled: Boolean = true,
    val total: Int = 0,
    val error: String? = null,
    val containers: List<DockerContainer> = emptyList(),
)

@Serializable
data class DockerLogsResponse(
    val name: String = "",
    val error: String? = null,
    val lines: List<String> = emptyList(),
)
