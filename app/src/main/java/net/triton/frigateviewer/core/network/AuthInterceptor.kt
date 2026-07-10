package net.triton.frigateviewer.core.network

import kotlinx.coroutines.runBlocking
import net.triton.frigateviewer.core.data.CredentialStore
import net.triton.frigateviewer.core.data.Server
import net.triton.frigateviewer.core.data.ServerRepository
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okio.IOException

/**
 * Attaches Authorization header per request. Handles three modes:
 *   - none: pass through
 *   - basic: HTTP Basic
 *   - frigate (JWT): Bearer token from CredentialStore
 *
 * Pairs with [TokenRefreshAuthenticator] for 401 retry/refresh.
 */
class AuthInterceptor(
    private val serverRepo: ServerRepository,
    private val credentialStore: CredentialStore,
) : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val server = runBlocking { serverRepo.activeServer() } ?: return chain.proceed(chain.request())
        val req =
            chain
                .request()
                .newBuilder()
                .applyAuth(server.id)
                .build()
        return chain.proceed(req)
    }

    private fun Request.Builder.applyAuth(serverId: String): Request.Builder {
        val auth = runBlocking { credentialStore.authHeader(serverId) }
        return if (auth != null) header("Authorization", auth) else this
    }
}

/**
 * On 401, attempts a single token refresh via POST /api/login for [server] then retries with
 * the new Bearer. If refresh fails (wrong credentials, server down, or nothing to refresh
 * from), returns null which terminates the retry and the call surfaces as
 * `ApiResult.HttpError(401, ...)` up in `safeApiCall`.
 *
 * Bound to a specific [server] (matching [PerServerAuthInterceptor]'s pattern) rather than
 * resolving `serverRepo.activeServer()` dynamically — this authenticator lives on one specific
 * per-server OkHttpClient (built in `FrigateClient.buildClient`), so it must always refresh
 * that same server's token even if the user has since switched the app's active server.
 */
class TokenRefreshAuthenticator(
    private val server: Server,
    private val credentialStore: CredentialStore,
    private val loginFn: suspend (Server) -> Boolean,
) : Authenticator {
    override fun authenticate(
        route: Route?,
        response: Response,
    ): Request? {
        if (response.priorResponse != null) return null // already retried once
        if (server.authMode != AuthMode.FRIGATE) return null
        val refreshed = runBlocking { loginFn(server) }
        if (!refreshed) return null
        val newHeader = runBlocking { credentialStore.authHeader(server.id) } ?: return null
        return response.request
            .newBuilder()
            .header("Authorization", newHeader)
            .build()
    }
}

enum class AuthMode { NONE, BASIC, FRIGATE }
