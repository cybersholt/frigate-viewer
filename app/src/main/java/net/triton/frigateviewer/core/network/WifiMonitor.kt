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

        private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
            val callback =
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
                }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
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

        /**
         * Force a fresh SSID read. Needed right after the user grants ACCESS_FINE_LOCATION from
         * the Servers settings screen — confirmed on-device that a permission grant does NOT
         * cause an already-registered [ConnectivityManager.NetworkCallback] to receive a new
         * [ConnectivityManager.NetworkCallback.onCapabilitiesChanged] call, and a direct
         * `getNetworkCapabilities(activeNetwork)` poll *also* still returns the
         * location-redacted "<unknown ssid>" immediately after granting (ConnectivityService
         * appears to sanitize location-sensitive fields once per capabilities snapshot, not
         * freshly per read). Unregistering and re-registering the callback is what actually
         * forces ConnectivityService to push a new snapshot computed against the just-granted
         * permission — verified end-to-end on the emulator (permission grant while
         * `currentSsid == null` → tapping through this path correctly revealed "AndroidWifi").
         */
        fun refresh() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                networkCallback?.let { cm.unregisterNetworkCallback(it) }
                registerNetworkCallback()
            } else {
                readLegacySsid()
            }
        }
    }
