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
) {
    fun baseUrl(): String {
        val portPart = port?.let { ":$it" }.orEmpty()
        val path = basePath.trim('/').let { if (it.isEmpty()) "" else "/$it" }
        return "$protocol://$host$portPart$path/"
    }
}
