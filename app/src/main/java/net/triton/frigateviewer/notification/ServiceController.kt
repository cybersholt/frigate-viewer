package net.triton.frigateviewer.notification

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for starting / stopping the MQTT foreground service.
 */
@Singleton
class ServiceController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun start() {
            context.startForegroundService(Intent(context, MqttForegroundService::class.java))
        }

        fun stop() {
            context.stopService(Intent(context, MqttForegroundService::class.java))
        }
    }
