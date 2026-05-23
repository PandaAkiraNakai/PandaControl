package io.github.pandaakira.apppanda.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import io.github.pandaakira.apppanda.MainActivity
import io.github.pandaakira.apppanda.PandaApp
import io.github.pandaakira.apppanda.R
import io.github.pandaakira.apppanda.data.models.SseEvent
import io.github.pandaakira.apppanda.sudo.SudoApprovalActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * ForegroundService que mantiene una conexión SSE al backend (via
 * PandaRepository.events) y muestra notificaciones del sistema para
 * alertas, servicios failed, boot, resume.
 *
 * Se activa/desactiva desde Setup con el toggle "Notificaciones push".
 * Al desactivar, llamar STOP con startService(intent.setAction(STOP_ACTION))
 * y el service se detiene a sí mismo.
 */
class AlertsService : Service() {

    companion object {
        const val CHANNEL_SERVICE = "panda-service"
        const val CHANNEL_ALERTS = "panda-alerts"
        const val CHANNEL_SUDO = "panda-sudo-urgent"
        const val ONGOING_ID = 1
        const val SUDO_NOTIF_BASE = 9000
        const val ACTION_START = "io.github.pandaakira.apppanda.START"
        const val ACTION_STOP = "io.github.pandaakira.apppanda.STOP"

        fun start(context: Context) {
            val intent = Intent(context, AlertsService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AlertsService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectorJob: Job? = null
    private var nextNotifId = 100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startInForeground()
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val notif = buildOngoing()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ONGOING_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(ONGOING_ID, notif)
        }
        if (collectorJob == null) {
            collectorJob = scope.launch {
                val app = applicationContext as PandaApp
                app.repository.events.collect { evt -> handleEvent(evt) }
            }
        }
    }

    override fun onDestroy() {
        collectorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ─── Notification handling ───────────────────────────────────────────────

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "Panda Control service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Conexión persistente con la torre." },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS, "Alertas de la torre",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Alertas CPU/RAM/temps, servicios failed, boot, resume." },
        )
        // Canal especial para solicitudes sudo: importancia máxima, sonido
        // de llamada, vibración.
        val sudoChannel = NotificationChannel(
            CHANNEL_SUDO, "Solicitudes sudo",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Aprobar/rechazar elevación de privilegios en la torre."
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300, 200, 300)
            val ringtone = RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_RINGTONE,
            ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audio = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(ringtone, audio)
        }
        nm.createNotificationChannel(sudoChannel)
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildOngoing(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Panda Control")
            .setContentText("Escuchando eventos de la torre")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun handleEvent(evt: SseEvent) {
        if (evt.type == "sudo_request") {
            handleSudoRequest(evt)
            return
        }
        val (title, body) = when (evt.type) {
            "alert" -> {
                val t = evt.title ?: "Alerta"
                val v = evt.valueStr ?: evt.value?.toString() ?: ""
                t to "${evt.key.orEmpty()} = $v"
            }
            "service_failed" -> "❌ Servicio falló" to (evt.unit ?: "")
            "session_new" -> "👤 Nueva sesión" to (evt.session ?: "")
            "boot" -> "🟢 Torre iniciada" to (evt.hostname ?: "")
            "resume" -> "💤→🟢 Resume" to "gap ≈ ${evt.gapS?.toInt() ?: "?"} s"
            else -> return
        }
        showNotification(title, body)
    }

    private fun handleSudoRequest(evt: SseEvent) {
        val rid = evt.rid ?: return
        val prompt = evt.prompt.orEmpty()
        val command = evt.command.orEmpty()
        val timeoutS = evt.timeoutS ?: 60

        // Hash estable del rid para reusar notifId si llega duplicado.
        val notifId = SUDO_NOTIF_BASE + (rid.hashCode() and 0x7fff)

        val activityIntent = Intent(this, SudoApprovalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(SudoApprovalActivity.EXTRA_RID, rid)
            putExtra(SudoApprovalActivity.EXTRA_PROMPT, prompt)
            putExtra(SudoApprovalActivity.EXTRA_COMMAND, command)
            putExtra(SudoApprovalActivity.EXTRA_TIMEOUT_S, timeoutS)
            putExtra(SudoApprovalActivity.EXTRA_NOTIF_ID, notifId)
        }
        val pi = PendingIntent.getActivity(
            this, rid.hashCode(), activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_SUDO)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🔐 Sudo en la torre")
            .setContentText("Tocá para aprobar o rechazar (${timeoutS}s)")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${prompt.ifBlank { "Solicitud de elevación de privilegios" }}\n\n" +
                        (if (command.isNotBlank()) "comando: $command" else ""),
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOngoing(false)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()

        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(notifId, notif)

        // También intentamos lanzar la Activity directo (Android puede ignorar
        // si la app está totalmente cerrada y sin full-screen-intent permitido).
        try {
            startActivity(activityIntent)
        } catch (_: Exception) {
            // OK: la notif urgente y el full-screen intent harán el resto.
        }
    }

    private fun showNotification(title: String, body: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val notif = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        nm.notify(nextNotifId++, notif)
    }
}
