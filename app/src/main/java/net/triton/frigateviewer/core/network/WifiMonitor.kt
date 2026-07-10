package net.triton.frigateviewer.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val _ssid = MutableStateFlow<String?>(null)
        val ssid: StateFlow<String?> = _ssid.asStateFlow()

        private fun isEmulator(): Boolean =
            Build.FINGERPRINT.contains("generic") ||
                Build.FINGERPRINT.contains("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                "google_sdk" == Build.PRODUCT

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerNetworkCallback()
            } else {
                readLegacySsid()
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun registerNetworkCallback() {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val request =
                NetworkRequest
                    .Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        caps: NetworkCapabilities,
                    ) {
                        val info = caps.transportInfo as? WifiInfo
                        val realSsid =
                            info
                                ?.ssid
                                ?.trim('"')
                                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

                        _ssid.value = realSsid ?: null
                    }

                    override fun onLost(network: Network) {
                        _ssid.value = null
                    }
                },
            )
        }

        @Suppress("DEPRECATION")
        private fun readLegacySsid() {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
            val info = wm.connectionInfo
            val realSsid =
                info
                    ?.ssid
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

            _ssid.value = realSsid ?: null
        }
    }
