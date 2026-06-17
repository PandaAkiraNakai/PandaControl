package io.github.pandaakira.apppanda.data

/** Grupo visual de categorías en la pantalla de ajustes. */
enum class NotifGroup(val label: String) {
    METRICS("Métricas"),
    SYSTEM("Sistema"),
}

/**
 * Categorías de notificación que el usuario puede activar/desactivar desde
 * Ajustes. El filtrado es 100 % en el cliente: el backend sigue mandando todo
 * por SSE y [io.github.pandaakira.apppanda.service.AlertsService] suprime las
 * categorías silenciadas antes de mostrar la notificación.
 *
 * El [id] de las alertas de métricas coincide con el `key` que manda el backend
 * en los eventos `alert`; el de los eventos de sistema coincide con el `type`
 * del SseEvent. Así el filtro mapea directo sin tablas extra.
 *
 * Las solicitudes sudo (`sudo_request`) NO están aquí a propósito: es el canal
 * urgente para aprobar elevación de privilegios y siempre debe llegar.
 */
enum class NotifCategory(
    val id: String,
    val label: String,
    val group: NotifGroup,
) {
    CPU("cpu_pct", "CPU alta", NotifGroup.METRICS),
    RAM("ram_pct", "RAM alta", NotifGroup.METRICS),
    GPU("gpu_pct", "GPU saturada", NotifGroup.METRICS),
    CPU_TEMP("cpu_temp_c", "Temp CPU", NotifGroup.METRICS),
    GPU_TEMP("gpu_temp_c", "Temp GPU", NotifGroup.METRICS),
    DISK("disk_pct", "Disco lleno", NotifGroup.METRICS),
    LOAD("load1_per_core", "Carga (load)", NotifGroup.METRICS),
    SERVICE_FAILED("service_failed", "Servicios fallidos", NotifGroup.SYSTEM),
    SESSION_NEW("session_new", "Nueva sesión", NotifGroup.SYSTEM),
    BOOT("boot", "Inicio del PC", NotifGroup.SYSTEM),
    RESUME("resume", "Resume / wake", NotifGroup.SYSTEM),
    CALENDARIO("calendario_recordatorio", "Recordatorios de agenda", NotifGroup.SYSTEM),
    ;

    companion object {
        /**
         * Categoría a la que pertenece un evento SSE. Para `alert` se usa el
         * `key` (cpu_pct, gpu_pct…); para el resto, el `type`. Devuelve null si
         * el evento no es filtrable (tipo desconocido o sin categoría): en ese
         * caso el service lo muestra igual, nunca lo silencia por accidente.
         */
        fun forEvent(type: String, key: String?): NotifCategory? {
            val target = if (type == "alert") key else type
            return entries.firstOrNull { it.id == target }
        }
    }
}
