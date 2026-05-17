package net.triton.frigateviewer.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import net.triton.frigateviewer.FrigateViewerApp
import net.triton.frigateviewer.MainActivity
import net.triton.frigateviewer.R

/**
 * Listens on Frigate's MQTT broker for events and posts notifications.
 *
 * v0.1 stub: starts foreground with the right dataSync service type (Android 14+ requirement)
 * and posts a persistent "listening" notification. MQTT client wiring lands in v0.2 once we
 * decide subscription pattern (`frigate/events` vs `frigate/reviews`) per Frigate version.
 */
@AndroidEntryPoint
class MqttForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        // TODO v0.2: connect hivemq-mqtt-client to active server, subscribe frigate/events,
        //             call NotificationManagerCompat with snapshot thumbnail loaded via Coil.
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(this, FrigateViewerApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(getString(R.string.notif_service_text))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val NOTIF_ID = 1001
    }
}
