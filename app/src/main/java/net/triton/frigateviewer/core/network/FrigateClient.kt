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
class FrigateClient @Inject constructor(
    private val credentialStore: CredentialStore,
    private val json: Json,
) {
    private data class Entry(val server: Server, val api: FrigateApi, val client: OkHttpClient)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val mutex = Mutex()

    private val _activeServerFlow = MutableStateFlow<Server?>(null)
    val activeServerFlow: StateFlow<Server?> = _activeServerFlow.asStateFlow()

    suspend fun setActive(server: Server?) {
        _activeServerFlow.value = server
    }

    suspend fun apiFor(server: Server): FrigateApi = mutex.withLock {
        val cached = cache[server.id]
        if (cached != null && cached.server == server) return@withLock cached.api
        val client = buildClient(server)
        val retrofit = Retrofit.Builder()
            .baseUrl(server.baseUrl())
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(FrigateApi::class.java)
        cache[server.id] = Entry(server, api, client)
        api
    }

    suspend fun invalidate(serverId: String) = mutex.withLock {
        cache.remove(serverId)
    }

    suspend fun clientFor(server: Server): OkHttpClient = mutex.withLock {
        cache[server.id]?.let { if (it.server == server) return@withLock it.client }
        apiFor(server)  // populates cache
        cache.getValue(server.id).client
    }

    private fun buildClient(server: Server): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .cookieJar(InMemoryCookieJar(server.id))
            .addInterceptor(logging)
            .addInterceptor(PerServerAuthInterceptor(server, credentialStore))

        val pinned = runBlocking { credentialStore.pinnedCert(server.id) }
        return TrustConfig.applyPinnedCertificate(builder, pinned).build()
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
        val raw = runBlocking { credentialStore.rawSecret(server.id) }
        val request = if (raw != null) {
            val header = when {
                raw.startsWith("JWT:") -> "Bearer ${raw.removePrefix("JWT:")}"
                server.authMode == AuthMode.BASIC -> "Basic " + java.util.Base64.getEncoder()
                    .encodeToString(raw.toByteArray(Charsets.UTF_8))
                else -> null
            }
            if (header != null) chain.request().newBuilder().header("Authorization", header).build()
            else chain.request()
        } else chain.request()
        return chain.proceed(request)
    }
}

/**
 * Process-lifetime in-memory cookie jar, keyed implicitly by the OkHttpClient instance
 * (which is itself keyed per server in [FrigateClient]). Tokens never hit disk.
 */
class InMemoryCookieJar(@Suppress("unused") private val tag: String) : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val now = System.currentTimeMillis()
        val list = store[host] ?: return emptyList()
        val valid = list.filter { it.expiresAt > now }
        if (valid.size != list.size) store[host] = valid.toMutableList()
        return valid
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val list = store.getOrPut(host) { mutableListOf() }
        cookies.forEach { c ->
            list.removeAll { it.name == c.name }
            list += c
        }
    }
}
