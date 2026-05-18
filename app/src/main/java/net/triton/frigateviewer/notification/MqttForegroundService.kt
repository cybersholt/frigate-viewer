package net.triton.frigateviewer.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.triton.frigateviewer.FrigateViewerApp
import net.triton.frigateviewer.MainActivity
import net.triton.frigateviewer.R
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.network.ApiResult
import java.util.UUID
import javax.inject.Inject

/**
 * MQTT subscriber → notification poster.
 *
 * Topics:
 *   frigate/events    (Frigate <0.14, JSON envelope { type, before, after })
 *   frigate/reviews   (Frigate 0.14+, JSON envelope per review item)
 *
 * Subscribes to both. The handler keys de-dupes by event id to handle redelivery.
 *
 * Service type = dataSync (Android 14+ enforced). Persistent notification on
 * CHANNEL_SERVICE keeps the process alive; event notifications go to CHANNEL_EVENTS.
 */
@AndroidEntryPoint
class MqttForegroundService : Service() {

    @Inject lateinit var serverRepo: ServerRepository
    @Inject lateinit var repo: FrigateRepository
    @Inject lateinit var json: Json

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var mqtt: Mqtt5AsyncClient? = null
    private val seenIds = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Long>?) = size > 256
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        scope.launch { connectAndSubscribe() }
        return START_STICKY
    }

    private suspend fun connectAndSubscribe() {
        val server = serverRepo.activeServer() ?: return
        val cfg = (repo.config() as? ApiResult.Success)?.data?.mqtt ?: return
        if (!cfg.enabled || cfg.host.isNullOrBlank()) return

        val client = MqttClient.builder()
            .useMqttVersion5()
            .identifier("frigate-viewer-${UUID.randomUUID()}")
            .serverHost(cfg.host)
            .serverPort(cfg.port)
            .automaticReconnectWithDefaultConfig()
            .buildAsync()

        mqtt = client
        client.connect().whenComplete { _, err ->
            if (err != null) return@whenComplete
            listOf("${cfg.topicPrefix}/events", "${cfg.topicPrefix}/reviews").forEach { topic ->
                client.subscribeWith()
                    .topicFilter(topic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .callback { publish ->
                        val payload = publish.payloadAsBytes.toString(Charsets.UTF_8)
                        handleMessage(topic, payload, server.baseUrl())
                    }
                    .send()
            }
        }
    }

    private fun handleMessage(topic: String, payload: String, baseUrl: String) {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
        // Frigate events envelope: { type: "new"|"update"|"end", after: {...event...} }
        val after = (root["after"] as? JsonObject) ?: root
        val id = after["id"]?.jsonPrimitive?.contentOrNull ?: return
        val camera = after["camera"]?.jsonPrimitive?.contentOrNull ?: "camera"
        val label = after["label"]?.jsonPrimitive?.contentOrNull ?: "object"
        val type = root["type"]?.jsonPrimitive?.contentOrNull ?: "event"
        if (type != "new") return  // post only on first appearance
        synchronized(seenIds) {
            if (seenIds.containsKey(id)) return
            seenIds[id] = System.currentTimeMillis()
        }
        postEventNotification(id, camera, label, baseUrl)
    }

    private fun postEventNotification(id: String, camera: String, label: String, baseUrl: String) {
        val pi = PendingIntent.getActivity(
            this, id.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("event_id", id)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, FrigateViewerApp.CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("$camera • $label")
            .setContentText("New event detected")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(this).also { nm ->
            if (nm.areNotificationsEnabled()) nm.notify(id.hashCode(), notif)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        mqtt?.disconnect()
        mqtt = null
        super.onDestroy()
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
