package net.triton.frigateviewer.core.data

import net.triton.frigateviewer.core.model.FrigateConfig
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.model.LoginRequest
import net.triton.frigateviewer.core.network.ApiResult
import net.triton.frigateviewer.core.network.FrigateClient
import net.triton.frigateviewer.core.network.safeApiCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single typed entry point for ViewModels. Every method returns an [ApiResult].
 * No exceptions cross this boundary.
 *
 * Resolves the active server via [ServerRepository] and binds Retrofit to it via
 * [FrigateClient]. ViewModels never see raw Retrofit calls.
 */
@Singleton
class FrigateRepository @Inject constructor(
    private val client: FrigateClient,
    private val credentialStore: CredentialStore,
    private val serverRepo: ServerRepository,
) {
    private suspend fun api() = serverRepo.activeServer()?.let { client.apiFor(it) }

    suspend fun config(): ApiResult<FrigateConfig> {
        val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
        return safeApiCall { api.config() }
    }

    suspend fun events(camera: String? = null, before: Long? = null, limit: Int = 50): ApiResult<List<FrigateEvent>> {
        val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
        return safeApiCall { api.events(camera = camera, before = before, limit = limit) }
    }

    suspend fun event(id: String): ApiResult<FrigateEvent> {
        val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
        return safeApiCall { api.event(id) }
    }

    suspend fun deleteEvent(id: String): ApiResult<Unit> {
        val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
        return safeApiCall { api.deleteEvent(id) }
    }

    suspend fun retainEvent(id: String, retain: Boolean): ApiResult<Unit> {
        val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
        return safeApiCall { if (retain) api.retainEvent(id) else api.unretainEvent(id) }
    }

    suspend fun login(serverId: String, username: String, password: String): Boolean {
        val server = serverRepo.all().firstOrNull { it.id == serverId } ?: return false
        val api = client.apiFor(server)
        val r = safeApiCall { api.login(LoginRequest(username, password)) }
        return when (r) {
            is ApiResult.Success -> {
                val token = r.data.token
                if (token != null) {
                    credentialStore.setBearer(serverId, token)
                    true
                } else {
                    // Frigate may set the cookie without echoing the token in body.
                    // Auth proceeds via the CookieJar.
                    true
                }
            }
            else -> false
        }
    }

    suspend fun logout(serverId: String) {
        credentialStore.delete(serverId)
        client.invalidate(serverId)
    }
}
