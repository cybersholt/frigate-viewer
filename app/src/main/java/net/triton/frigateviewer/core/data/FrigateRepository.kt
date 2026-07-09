package net.triton.frigateviewer.core.data

import net.triton.frigateviewer.core.model.FrigateConfig
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.model.LoginRequest
import net.triton.frigateviewer.core.model.RecordingGap
import net.triton.frigateviewer.core.model.RecordingSegment
import net.triton.frigateviewer.core.model.ReviewSegment
import net.triton.frigateviewer.core.network.ApiResult
import net.triton.frigateviewer.core.network.FrigateClient
import net.triton.frigateviewer.core.network.WifiMonitor
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
class FrigateRepository
    @Inject
    constructor(
        private val client: FrigateClient,
        private val credentialStore: CredentialStore,
        private val serverRepo: ServerRepository,
        private val wifiMonitor: WifiMonitor,
    ) {
        private var cachedConfig: FrigateConfig? = null
        private var cachedStreams: Map<String, kotlinx.serialization.json.JsonElement>? = null
        private var lastServerId: String? = null

        private suspend fun api() =
            serverRepo.activeServer()?.let { s ->
                if (s.id != lastServerId) {
                    // Server switched, clear cache
                    cachedConfig = null
                    cachedStreams = null
                    lastServerId = s.id
                }
                if (s.authMode == net.triton.frigateviewer.core.network.AuthMode.FRIGATE) {
                    reloginIfNecessary(s)
                }
                client.apiFor(s, wifiMonitor.ssid.value)
            }

        private suspend fun reloginIfNecessary(server: Server) {
            val secret = credentialStore.rawSecret(server.id) ?: return
            if (secret.startsWith(CredentialStore.BEARER_PREFIX)) return
            // Frigate often authenticates via session cookie rather than echoing a JWT in the
            // login response body, in which case the stored secret stays a raw password forever.
            // Without this check we'd re-POST /api/login on every single request. Skip re-login
            // while a live cookie is held; a call will naturally re-trigger login once it expires.
            if (client.hasSessionCookie(server)) return
            val parts = secret.split(":", limit = 2)
            val user = if (parts.size == 2) parts[0] else server.username ?: ""
            val pass = if (parts.size == 2) parts[1] else secret
            login(server.id, user, pass)
        }

        suspend fun config(forceRefresh: Boolean = false): ApiResult<FrigateConfig> {
            if (!forceRefresh && cachedConfig != null) return ApiResult.Success(cachedConfig!!)
            val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
            return safeApiCall { api.config() }.also {
                if (it is ApiResult.Success) cachedConfig = it.data
            }
        }

        suspend fun events(
            camera: String? = null,
            label: String? = null,
            zone: String? = null,
            after: Double? = null,
            before: Long? = null,
            limit: Int = 50,
        ): ApiResult<List<FrigateEvent>> {
            val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
            return safeApiCall { api.events(camera = camera, label = label, zone = zone, after = after, before = before, limit = limit) }
        }

        suspend fun event(id: String): ApiResult<FrigateEvent> {
            val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
            return safeApiCall { api.event(id) }
        }

        suspend fun deleteEvent(id: String): ApiResult<Unit> {
            val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
            return safeApiCall { api.deleteEvent(id) }
        }

        suspend fun retainEvent(
            id: String,
            retain: Boolean,
        ): ApiResult<Unit> {
            val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
            return safeApiCall { if (retain) api.retainEvent(id) else api.unretainEvent(id) }
        }

        /** Physical recording segments for [camera] — drives VOD seek math (DynamicVideoPlayer parity). */
        suspend fun recordings(
            camera: String,
            after: Double? = null,
            before: Double? = null,
        ): ApiResult<List<RecordingSegment>> {
            val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
            return safeApiCall { api.recordings(camera = camera, after = after, before = before) }
        }

        /** Gaps in the recording track, for drawing "no recording" ranges on the timeline. */
        suspend fun recordingGaps(
            camera: String,
            after: Double? = null,
            before: Double? = null,
            scale: Int? = null,
        ): ApiResult<List<RecordingGap>> {
            val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
            return safeApiCall { api.recordingGaps(cameras = camera, after = after, before = before, scale = scale) }
        }

        /** Severity-classified review segments (alert/detection/significant_motion). */
        suspend fun review(
            camera: String,
            after: Double? = null,
            before: Double? = null,
        ): ApiResult<List<ReviewSegment>> {
            val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
            return safeApiCall { api.review(cameras = camera, after = after, before = before) }
        }

        suspend fun go2rtcStreams(forceRefresh: Boolean = false): ApiResult<Map<String, kotlinx.serialization.json.JsonElement>> {
            if (!forceRefresh && cachedStreams != null) return ApiResult.Success(cachedStreams!!)
            val api = api() ?: return ApiResult.NetworkError(IllegalStateException("No active server"))
            return safeApiCall { api.go2rtcStreams() }.also {
                if (it is ApiResult.Success) cachedStreams = it.data
            }
        }

        suspend fun login(
            serverId: String,
            username: String,
            password: String,
        ): Boolean {
            val server = serverRepo.all().firstOrNull { it.id == serverId } ?: return false
            val api = client.apiFor(server, wifiMonitor.ssid.value)
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

                else -> {
                    false
                }
            }
        }

        suspend fun logout(serverId: String) {
            credentialStore.delete(serverId)
            client.invalidate(serverId)
        }
    }
