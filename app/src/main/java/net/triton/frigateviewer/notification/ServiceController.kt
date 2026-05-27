package net.triton.frigateviewer.notification

import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for starting / stopping the MQTT foreground service.
 * Centralizes the Build.VERSION check.
 */
@Singleton
class ServiceController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun start() {
            val intent = Intent(context, MqttForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop() {
            context.stopService(Intent(context, MqttForegroundService::class.java))
        }
    }
