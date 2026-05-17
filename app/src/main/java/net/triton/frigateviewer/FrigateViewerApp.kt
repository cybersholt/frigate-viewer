package net.triton.frigateviewer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FrigateViewerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val eventsChannel = NotificationChannel(
            CHANNEL_EVENTS,
            getString(R.string.notif_channel_events),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.notif_channel_events_desc)
        }
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.notif_service_title),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannels(listOf(eventsChannel, serviceChannel))
    }

    companion object {
        const val CHANNEL_EVENTS = "frigate_events"
        const val CHANNEL_SERVICE = "frigate_service"
    }
}
