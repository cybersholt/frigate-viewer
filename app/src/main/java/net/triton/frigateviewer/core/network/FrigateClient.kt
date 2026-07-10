package net.triton.frigateviewer.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import net.triton.frigateviewer.BuildConfig
import net.triton.frigateviewer.core.data.CredentialStore
import net.triton.frigateviewer.core.data.Server
import net.triton.frigateviewer.core.model.LoginRequest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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

        /**
         * True if a live, unexpired session cookie is already held for [server]. Lets
         * [net.triton.frigateviewer.core.data.FrigateRepository] skip redundant re-logins on
         * every request when Frigate authenticates via cookie (no JWT echoed in the login body).
         */
        suspend fun hasSessionCookie(server: Server): Boolean =
            mutex.withLock {
                ensureFor(server, null)
                (clients[server.id]?.cookieJar as? SessionCookieJar)?.hasCookies() ?: false
            }

        suspend fun invalidate(serverId: String) =
            mutex.withLock {
                servers.remove(serverId)
                apis.remove(serverId)
                clients.remove(serverId)
                cachedEffectiveUrls.remove(serverId)
            }

        /** MUST be called while holding [mutex]. */
        private suspend fun ensureFor(
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
            try {
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
            } catch (e: Exception) {
                // If the URL is malformed, we log it and avoid crashing the whole app.
                // The user can then at least open settings and fix it.
                android.util.Log.e("FrigateClient", "Failed to create API for server ${server.id} with URL $effectiveUrl", e)
            }
        }

        private suspend fun buildClient(server: Server): OkHttpClient {
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
                    .cookieJar(SessionCookieJar(server.id, credentialStore))
                    .addInterceptor(logging)
                    .addInterceptor(PerServerAuthInterceptor(server, credentialStore))
                    .authenticator(TokenRefreshAuthenticator(server, credentialStore, ::refreshToken))

            if (server.allowUntrusted) {
                TrustConfig.applyAllowUntrusted(builder)
            } else {
                // ensureFor/buildClient are both suspend now (called only from the suspend
                // mutex.withLock blocks in apiFor/clientFor/hasSessionCookie above), so this can
                // call CredentialStore directly instead of runBlocking-wrapping it — the
                // runBlocking here was flagged as dead weight, not a genuine sync-adapter need
                // (contrast PerServerAuthInterceptor/SessionCookieJar below, which really do
                // bridge a synchronous OkHttp SPI and keep their runBlocking for that reason).
                val pinned = credentialStore.pinnedCert(server.id)
                TrustConfig.applyPinnedCertificate(builder, pinned)
            }
            return builder.build()
        }

        /**
         * Silent POST api/login refresh, used only by [TokenRefreshAuthenticator] on a 401.
         * Duplicates the small user/pass-splitting step in
         * [net.triton.frigateviewer.core.data.FrigateRepository.reloginIfNecessary] rather than
         * calling into FrigateRepository directly — FrigateRepository already depends on
         * FrigateClient, so the reverse dependency would be circular in the Hilt graph.
         */
        private suspend fun refreshToken(server: Server): Boolean {
            val secret = credentialStore.rawSecret(server.id) ?: return false
            if (secret.startsWith(CredentialStore.BEARER_PREFIX)) return false
            val parts = secret.split(":", limit = 2)
            val username = if (parts.size == 2) parts[0] else server.username ?: ""
            val password = if (parts.size == 2) parts[1] else secret
            val api = apiFor(server)
            return when (val result = safeApiCall { api.login(LoginRequest(username, password)) }) {
                is ApiResult.Success -> {
                    result.data.token?.let { credentialStore.setBearer(server.id, it) }
                    true
                }

                else -> {
                    false
                }
            }
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
 * Per-server cookie jar, keyed implicitly by the OkHttpClient instance (itself keyed per
 * server in [FrigateClient]). Cached in memory for fast synchronous OkHttp access, and
 * persisted AEAD-encrypted via [CredentialStore] so a Frigate session cookie survives an
 * app restart instead of forcing a fresh `POST /api/login` every cold start.
 */
class SessionCookieJar(
    private val serverId: String,
    private val credentialStore: CredentialStore,
) : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loaded = false

    // Synchronous CookieJar interface can't suspend; this mirrors the runBlocking-in-interceptor
    // pattern already used by PerServerAuthInterceptor to bridge CredentialStore's suspend API.
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            runBlocking { credentialStore.cookies(serverId) }
                ?.lineSequence()
                ?.forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEach
                    val host = line.substring(0, tab)
                    val cookieStr = line.substring(tab + 1)
                    runCatching { Cookie.parse("https://$host/".toHttpUrl(), cookieStr) }
                        .getOrNull()
                        ?.let { store.getOrPut(host) { mutableListOf() } += it }
                }
            loaded = true
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        ensureLoaded()
        return synchronized(lock) {
            val host = url.host
            val now = System.currentTimeMillis()
            val list = store[host] ?: return emptyList()
            val valid = list.filter { it.expiresAt > now && it.matches(url) }
            if (valid.size != list.size) {
                store[host] = valid.toMutableList()
            }
            valid
        }
    }

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        ensureLoaded()
        if (cookies.isEmpty()) return
        synchronized(lock) {
            val host = url.host
            val list = store.getOrPut(host) { mutableListOf() }
            cookies.forEach { c ->
                list.removeAll { it.name == c.name }
                list += c
            }
        }
        persist()
    }

    /** True if any host has at least one unexpired cookie (i.e. an active Frigate session). */
    fun hasCookies(): Boolean {
        ensureLoaded()
        return synchronized(lock) {
            val now = System.currentTimeMillis()
            store.values.any { list -> list.any { it.expiresAt > now } }
        }
    }

    private fun persist() {
        val snapshot =
            synchronized(lock) {
                store.entries.flatMap { (host, list) -> list.map { host to it } }
            }
        ioScope.launch {
            val serialized = snapshot.joinToString("\n") { (host, cookie) -> "$host\t$cookie" }
            credentialStore.setCookies(serverId, serialized)
        }
    }
}
