package net.triton.frigateviewer.core.data

import kotlinx.serialization.Serializable
import net.triton.frigateviewer.core.network.AuthMode

@Serializable
data class Server(
    val id: String,
    val name: String,
    val protocol: String = "https",
    val host: String,
    val port: Int? = null,
    val basePath: String = "",
    val authMode: AuthMode = AuthMode.NONE,
    val username: String? = null,
    /** Whether the user has pinned a custom certificate for this host (stored separately). */
    val hasPinnedCert: Boolean = false,
    /** Whether to allow untrusted (self-signed) certificates for this server. */
    val allowUntrusted: Boolean = false,
    /** The RTSP port for this server (default is 8554). */
    val rtspPort: Int = 8554,
    /**
     * Optional host override for RTSP/go2rtc. Set this to the LAN IP when the HTTP
     * server is accessed via a domain that doesn't forward port 8554.
     * Falls back to [host] when blank/null.
     */
    val rtspHost: String? = null,
    /** When on one of [localNetworkSsids], use this URL instead of the main server URL. */
    val localNetworkUrl: String? = null,
    /** Wi-Fi SSIDs on which [localNetworkUrl] should be used. */
    val localNetworkSsids: List<String> = emptyList(),
) {
    fun baseUrl(): String {
        val cleanHost = host.substringBefore(':')
        val hostPort = host.substringAfter(':', "")

        val finalPort =
            when {
                port != null -> port
                hostPort.isNotEmpty() -> hostPort.toIntOrNull()
                else -> null
            }

        // Omit port if it's the default for the protocol
        val isDefaultPort = (protocol == "https" && finalPort == 443) || (protocol == "http" && finalPort == 80)
        val portPart = if (finalPort != null && !isDefaultPort) ":$finalPort" else ""
        
        val path = basePath.trim('/').let { if (it.isEmpty()) "" else "/$it" }
        return "$protocol://$cleanHost$portPart$path/"
    }

    fun effectiveBaseUrl(currentSsid: String?): String {
        if (localNetworkUrl != null && currentSsid != null && currentSsid in localNetworkSsids) {
            return if (localNetworkUrl.endsWith("/")) localNetworkUrl else "$localNetworkUrl/"
        }
        return baseUrl()
    }
}
