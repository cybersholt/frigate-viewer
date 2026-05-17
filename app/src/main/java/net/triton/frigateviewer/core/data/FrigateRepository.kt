package net.triton.frigateviewer.core.data

import net.triton.frigateviewer.core.model.FrigateConfig
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.model.LoginRequest
import net.triton.frigateviewer.core.network.ApiResult
import net.triton.frigateviewer.core.network.FrigateApi
import net.triton.frigateviewer.core.network.safeApiCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single typed entry point for ViewModels. Every method returns an [ApiResult].
 * No exceptions cross this boundary.
 */
@Singleton
class FrigateRepository @Inject constructor(
    private val api: FrigateApi,
    private val credentialStore: CredentialStore,
    private val serverRepo: ServerRepository,
) {
    suspend fun config(): ApiResult<FrigateConfig> = safeApiCall { api.config() }

    suspend fun events(camera: String? = null, limit: Int = 50): ApiResult<List<FrigateEvent>> =
        safeApiCall { api.events(camera = camera, limit = limit) }

    suspend fun deleteEvent(id: String): ApiResult<Unit> = safeApiCall { api.deleteEvent(id) }
    suspend fun retainEvent(id: String, retain: Boolean): ApiResult<Unit> =
        safeApiCall { if (retain) api.retainEvent(id) else api.unretainEvent(id) }

    /** Login + persist JWT. Returns true on success. */
    suspend fun login(serverId: String, username: String, password: String): Boolean {
        val result = safeApiCall { api.login(LoginRequest(username, password)) }
        return when (result) {
            is ApiResult.Success -> {
                result.data.token?.let { credentialStore.setBearer(serverId, it) } != null
            }
            else -> false
        }
    }
}
