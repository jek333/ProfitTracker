package com.jek.sq5monitor.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.jek.sq5monitor.MainActivity
import com.jek.sq5monitor.R
import com.jek.sq5monitor.Sq5MonitorApp
import com.jek.sq5monitor.core.monitor.Alert
import com.jek.sq5monitor.core.monitor.AlertSeverity
import com.jek.sq5monitor.core.obd.Pids
import com.jek.sq5monitor.core.obd.Signals
import com.jek.sq5monitor.core.obd.Units
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and therefore the adapter connection, logging and
 * alerts) alive while the app is in the background, e.g. when navigation is on screen.
 */
class MonitorService : LifecycleService() {

    private val container get() = (application as Sq5MonitorApp).container
    private var lastStatusText = ""
    private var lastStatusUpdateMs = 0L
    private val notifiedAlerts = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            combine(container.connection.state, container.hub.latest, container.hub.alerts) { state, sample, alerts ->
                Triple(state, sample, alerts)
            }.collect { (state, sample, alerts) ->
                if (state !is ConnectionState.Connected) {
                    stopSelf()
                    return@collect
                }
                updateStatus(state.session, sample?.values.orEmpty())
                notifyAlerts(alerts)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_DISCONNECT) {
            container.connection.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, STATUS_NOTIFICATION_ID, statusNotification("Connected"), type)
        return START_NOT_STICKY
    }

    private fun updateStatus(session: VehicleSession, values: Map<String, Double>) {
        val units = container.settings.value.units
        val parts = listOf(Signals.pidId(Pids.COOLANT_TEMP), Signals.pidId(Pids.OIL_TEMP), Signals.BATTERY).mapNotNull { id ->
            val signal = Signals[id] ?: return@mapNotNull null
            val value = values[id] ?: return@mapNotNull null
            "${signal.shortName} ${Units.display(value, signal.quantity, units, signal.decimals)}"
        }
        val text = (listOf(session.target.label) + parts).joinToString(" · ")
        val now = System.currentTimeMillis()
        if (text == lastStatusText || now - lastStatusUpdateMs < STATUS_UPDATE_INTERVAL_MS) return
        lastStatusText = text
        lastStatusUpdateMs = now
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            getSystemService(NotificationManager::class.java).notify(STATUS_NOTIFICATION_ID, statusNotification(text))
        }
    }

    private fun notifyAlerts(alerts: List<Alert>) {
        val manager = getSystemService(NotificationManager::class.java)
        // Forget cleared alerts so they notify again if they come back.
        notifiedAlerts.retainAll(alerts.map { it.id }.toSet())
        for (alert in alerts) {
            if (!notifiedAlerts.add(alert.id)) continue
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) continue
            val notification = NotificationCompat.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(alert.title)
                .setContentText(alert.detail)
                .setPriority(if (alert.severity == AlertSeverity.CRITICAL) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
            manager.notify(ALERT_NOTIFICATION_BASE + alert.id.hashCode().and(0xFFFF), notification)
        }
    }

    private fun statusNotification(text: String): Notification {
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SQ5 Monitor connected")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Disconnect", disconnect)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val ACTION_DISCONNECT = "com.jek.sq5monitor.DISCONNECT"
        private const val STATUS_CHANNEL = "connection"
        private const val ALERT_CHANNEL = "alerts"
        private const val STATUS_NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_BASE = 1000
        private const val STATUS_UPDATE_INTERVAL_MS = 2_000L

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(STATUS_CHANNEL, "Connection status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while connected to the car"
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, "Vehicle alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Overheating, charging problems and check engine light"
                },
            )
        }

        /** Starts the service; ignored if Android refuses (e.g. app already in the background). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: the connection still works while the app is open.
            }
        }
    }
}
