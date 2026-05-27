package net.triton.frigateviewer.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import net.triton.frigateviewer.BuildConfig
import net.triton.frigateviewer.core.data.CredentialStore
import net.triton.frigateviewer.core.data.Server
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-server Retrofit + OkHttp factory.
 *
 * Frigate base URL is per-server, not global. We can't bake it into a single Retrofit
 * instance. This factory holds a cache keyed by server id and rebuilds on:
 *   - server URL change
 *   - pinned cert change
 *   - explicit invalidate after credential rotation
 *
 * All ViewModels go through [active] which returns a FrigateApi bound to the active server.
 */
@Singleton
class FrigateClient
    @Inject
    constructor(
        private val credentialStore: CredentialStore,
        private val json: Json,
    ) {
        private val servers = ConcurrentHashMap<String, Server>()
        private val apis = ConcurrentHashMap<String, FrigateApi>()
        private val clients = ConcurrentHashMap<String, OkHttpClient>()
        private val cachedEffectiveUrls = ConcurrentHashMap<String, String>()
        private val mutex = Mutex()

        private val _activeServerFlow = MutableStateFlow<Server?>(null)
        val activeServerFlow: StateFlow<Server?> = _activeServerFlow.asStateFlow()

        suspend fun setActive(server: Server?) {
            _activeServerFlow.value = server
        }

        suspend fun apiFor(
            server: Server,
            ssid: String? = null,
        ): FrigateApi =
            mutex.withLock {
                ensureFor(server, ssid)
                apis.getValue(server.id)
            }

        suspend fun clientFor(server: Server): OkHttpClient =
            mutex.withLock {
                ensureFor(server, null)
                clients.getValue(server.id)
            }

        suspend fun invalidate(serverId: String) =
            mutex.withLock {
                servers.remove(serverId)
                apis.remove(serverId)
                clients.remove(serverId)
                cachedEffectiveUrls.remove(serverId)
            }

        /** MUST be called while holding [mutex]. */
        private fun ensureFor(
            server: Server,
            ssid: String?,
        ) {
            val effectiveUrl = server.effectiveBaseUrl(ssid)
            if (servers[server.id] == server &&
                apis.containsKey(server.id) &&
                cachedEffectiveUrls[server.id] == effectiveUrl
            ) {
                return
            }
            val client = buildClient(server)
            val retrofit =
                Retrofit
                    .Builder()
                    .baseUrl(effectiveUrl)
                    .client(client)
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
            servers[server.id] = server
            clients[server.id] = client
            apis[server.id] = retrofit.create(FrigateApi::class.java)
            cachedEffectiveUrls[server.id] = effectiveUrl
        }

        private fun buildClient(server: Server): OkHttpClient {
            val logging =
                HttpLoggingInterceptor().apply {
                    level =
                        if (BuildConfig.DEBUG) {
                            HttpLoggingInterceptor.Level.BASIC
                        } else {
                            HttpLoggingInterceptor.Level.NONE
                        }
                }
            val builder =
                OkHttpClient
                    .Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .cookieJar(InMemoryCookieJar(server.id))
                    .addInterceptor(logging)
                    .addInterceptor(PerServerAuthInterceptor(server, credentialStore))

            if (server.allowUntrusted) {
                TrustConfig.applyAllowUntrusted(builder)
            } else {
                val pinned = runBlocking { credentialStore.pinnedCert(server.id) }
                TrustConfig.applyPinnedCertificate(builder, pinned)
            }
            return builder.build()
        }
    }

/**
 * Per-server auth header. Pulled fresh per request so JWT rotation is picked up
 * without rebuilding the OkHttp client.
 */
class PerServerAuthInterceptor(
    private val server: Server,
    private val credentialStore: CredentialStore,
) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val path = request.url.encodedPath

        // Skip auth header for login (bootstrapping) or WebSocket.
        // WebRTC WebSocket in go2rtc/Frigate often fails with 403 if an Authorization header
        // is present on the upgrade request, as it prefers session cookies.
        if (path.endsWith("api/login") || path.contains("api/ws") || path.endsWith("/ws")) {
            return chain.proceed(request)
        }

        val raw = runBlocking { credentialStore.rawSecret(server.id) }
        val authenticatedRequest =
            if (raw != null) {
                val headerValue =
                    when {
                        raw.startsWith(CredentialStore.BEARER_PREFIX) -> {
                            "Bearer ${raw.removePrefix(CredentialStore.BEARER_PREFIX)}"
                        }

                        server.authMode == AuthMode.BASIC -> {
                            "Basic " +
                                java.util.Base64
                                    .getEncoder()
                                    .encodeToString(raw.toByteArray(Charsets.UTF_8))
                        }

                        else -> {
                            null
                        }
                    }
                if (headerValue != null) {
                    request.newBuilder().header("Authorization", headerValue).build()
                } else {
                    request
                }
            } else {
                request
            }
        return chain.proceed(authenticatedRequest)
    }
}

/**
 * Process-lifetime in-memory cookie jar, keyed implicitly by the OkHttpClient instance
 * (which is itself keyed per server in [FrigateClient]). Tokens never hit disk.
 */
class InMemoryCookieJar(
    @Suppress("unused") private val tag: String,
) : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val now = System.currentTimeMillis()
        val list = store[host] ?: return emptyList()
        val valid = list.filter { it.expiresAt > now && it.matches(url) }
        if (valid.size != list.size) store[host] = valid.toMutableList()
        return valid
    }

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        val host = url.host
        val list = store.getOrPut(host) { mutableListOf() }
        cookies.forEach { c ->
            list.removeAll { it.name == c.name }
            list += c
        }
    }
}
