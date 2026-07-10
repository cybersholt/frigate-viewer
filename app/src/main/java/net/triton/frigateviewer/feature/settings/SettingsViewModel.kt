package net.triton.frigateviewer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.data.CredentialStore
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.Server
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.data.UserSettingsRepository
import net.triton.frigateviewer.core.network.ApiResult
import net.triton.frigateviewer.core.network.AuthMode
import net.triton.frigateviewer.core.network.FrigateClient
import net.triton.frigateviewer.core.network.WifiMonitor
import net.triton.frigateviewer.core.network.safeApiCall
import net.triton.frigateviewer.notification.ServiceController
import java.util.UUID
import javax.inject.Inject

/** Result of a manual "test connection" tap on a server row (Servers settings page). */
data class ConnectionTestResult(
    val serverId: String,
    val success: Boolean,
    val latencyMs: Long?,
    val message: String,
)

data class ServerForm(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: String = "https",
    val host: String = "",
    val port: String = "",
    val basePath: String = "",
    val authMode: AuthMode = AuthMode.NONE,
    val username: String = "",
    val password: String = "",
    val allowUntrusted: Boolean = false,
    val rtspPort: String = "8554",
    val rtspHost: String = "",
    val localNetworkUrl: String = "",
    val localNetworkSsids: List<String> = emptyList(),
)

data class SettingsUiState(
    val servers: List<Server> = emptyList(),
    val activeId: String? = null,
    val testResult: String? = null,
    val preferSubStreamGrid: Boolean = true,
    val preferSubStreamFullscreen: Boolean = false,
    val themeMode: String = "SYSTEM",
    val accentColor: Long = 0xFF6750A4,
    val useWallpaperColor: Boolean = false,
    val paletteStyle: String = "TonalSpot",
    val amoledBlack: Boolean = false,
    val autoRefreshCameras: Boolean = false,
    val cameraGridColumns: Int = 2,
    val hideEventImageInStream: Boolean = false,
    val autoLandscapeOnStream: Boolean = false,
    val autoRefreshInterval: Int = 3,
    val eventPhotoPreference: String = "snapshot",
    val gridStreamType: String = "snapshot",
    val fullscreenStreamType: String = "webrtc",
    val keepOffscreenTilesAlive: Boolean = false,
    val rtspReconnectAttempts: Int = 2,
    val rtspReconnectBaseDelaySeconds: Int = 2,
    val showBoundingBoxes: Boolean = true,
    val eventGridColumns: Int = 1,
    val dateFormat: String = "descriptive",
    val contrastLevel: Int = 0,
    val cardCornerRadius: Int = 12,
    val cardBorderWidth: Int = 0,
    val customAccentColors: List<Long> = emptyList(),
    val showLastImageWhileLoading: Boolean = true,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val serverRepo: ServerRepository,
        private val userSettingsRepo: UserSettingsRepository,
        private val credentialStore: CredentialStore,
        private val repo: FrigateRepository,
        private val client: FrigateClient,
        private val serviceController: ServiceController,
        private val wifiMonitor: WifiMonitor,
    ) : ViewModel() {
        val currentSsid: kotlinx.coroutines.flow.StateFlow<String?> = wifiMonitor.ssid

        /** Call right after the user grants ACCESS_FINE_LOCATION — see [WifiMonitor.refresh]. */
        fun refreshWifiSsid() = wifiMonitor.refresh()

        val state: kotlinx.coroutines.flow.StateFlow<SettingsUiState> =
            combine<Any?, SettingsUiState>(
                serverRepo.servers,
                serverRepo.activeServerId,
                userSettingsRepo.preferSubStreamGrid,
                userSettingsRepo.themeMode,
                userSettingsRepo.accentColor,
                userSettingsRepo.useWallpaperColor,
                userSettingsRepo.paletteStyle,
                userSettingsRepo.amoledBlack,
                userSettingsRepo.autoRefreshCameras,
                userSettingsRepo.cameraGridColumns,
                userSettingsRepo.hideEventImageInStream,
                userSettingsRepo.autoLandscapeOnStream,
                userSettingsRepo.autoRefreshInterval,
                userSettingsRepo.eventPhotoPreference,
                userSettingsRepo.gridStreamType,
                userSettingsRepo.showBoundingBoxes,
                userSettingsRepo.eventGridColumns,
                userSettingsRepo.dateFormat,
                userSettingsRepo.contrastLevel,
                userSettingsRepo.cardCornerRadius,
                userSettingsRepo.cardBorderWidth,
                userSettingsRepo.customAccentColors,
                userSettingsRepo.showLastImageWhileLoading,
                userSettingsRepo.fullscreenStreamType,
                userSettingsRepo.preferSubStreamFullscreen,
                userSettingsRepo.keepOffscreenTilesAlive,
                userSettingsRepo.rtspReconnectAttempts,
                userSettingsRepo.rtspReconnectBaseDelaySeconds,
            ) { args ->
                @Suppress("UNCHECKED_CAST")
                SettingsUiState(
                    servers = args[0] as List<Server>,
                    activeId = args[1] as String?,
                    preferSubStreamGrid = args[2] as Boolean,
                    themeMode = args[3] as String,
                    accentColor = args[4] as Long,
                    useWallpaperColor = args[5] as Boolean,
                    paletteStyle = args[6] as String,
                    amoledBlack = args[7] as Boolean,
                    autoRefreshCameras = args[8] as Boolean,
                    cameraGridColumns = args[9] as Int,
                    hideEventImageInStream = args[10] as Boolean,
                    autoLandscapeOnStream = args[11] as Boolean,
                    autoRefreshInterval = args[12] as Int,
                    eventPhotoPreference = args[13] as String,
                    gridStreamType = args[14] as String,
                    showBoundingBoxes = args[15] as Boolean,
                    eventGridColumns = args[16] as Int,
                    dateFormat = args[17] as String,
                    contrastLevel = args[18] as Int,
                    cardCornerRadius = args[19] as Int,
                    cardBorderWidth = args[20] as Int,
                    customAccentColors = args[21] as List<Long>,
                    showLastImageWhileLoading = args[22] as Boolean,
                    fullscreenStreamType = args[23] as String,
                    preferSubStreamFullscreen = args[24] as Boolean,
                    keepOffscreenTilesAlive = args[25] as Boolean,
                    rtspReconnectAttempts = args[26] as Int,
                    rtspReconnectBaseDelaySeconds = args[27] as Int,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

        @Suppress("unused")
        private val _testResult = MutableStateFlow<String?>(null)

        /**
         * Builds the [Server] a [ServerForm] would save as, without persisting anything —
         * shared by [saveServer] and [testDraftConnection] so the host:port-extraction logic
         * (and everything else derived from the form) has exactly one implementation.
         */
        private fun ServerForm.toDraftServer(): Server {
            val inputHost = host.trim()
            val (finalHost, extractedPort) =
                if (inputHost.contains(":")) {
                    val parts = inputHost.split(":")
                    parts[0] to parts[1].toIntOrNull()
                } else {
                    inputHost to null
                }

            // If the user entered a port in the dedicated field, use it.
            // If the field is blank, check if we extracted a port from the host field.
            // Otherwise, it remains null (using protocol defaults).
            val resolvedPort = port.trim().let { if (it.isEmpty()) extractedPort else it.toIntOrNull() }

            return Server(
                id = id,
                name = name.ifBlank { finalHost },
                protocol = protocol,
                host = finalHost,
                port = resolvedPort,
                basePath = basePath.trim(),
                authMode = authMode,
                username = username.ifBlank { null },
                allowUntrusted = allowUntrusted,
                rtspPort = rtspPort.toIntOrNull() ?: 8554,
                rtspHost = rtspHost.trim().ifBlank { null },
                localNetworkUrl = localNetworkUrl.trim().ifBlank { null },
                localNetworkSsids = localNetworkSsids,
            )
        }

        fun saveServer(form: ServerForm) {
            viewModelScope.launch {
                val server = form.toDraftServer()
                serverRepo.upsert(server)
                client.invalidate(server.id)
                if (form.password.isNotBlank()) {
                    val raw =
                        if (form.authMode == AuthMode.BASIC) {
                            "${form.username}:${form.password}"
                        } else {
                            form.password
                        }
                    credentialStore.setPassword(server.id, raw)
                }
                if (serverRepo.activeServer() == null) serverRepo.setActive(server.id)
                if (form.authMode == AuthMode.FRIGATE) {
                    repo.login(server.id, form.username, form.password)
                }
                serviceController.start()
            }
        }

        fun setActive(id: String) {
            viewModelScope.launch {
                serverRepo.setActive(id)
                serviceController.stop()
                serviceController.start()
            }
        }

        fun delete(id: String) {
            viewModelScope.launch {
                serverRepo.delete(id)
                credentialStore.delete(id)
                client.invalidate(id)
            }
        }

        fun logout(id: String) {
            viewModelScope.launch {
                repo.logout(id)
                serviceController.stop()
            }
        }

        fun setPreferSubStreamGrid(prefer: Boolean) = viewModelScope.launch { userSettingsRepo.setPreferSubStreamGrid(prefer) }

        fun setPreferSubStreamFullscreen(prefer: Boolean) = viewModelScope.launch { userSettingsRepo.setPreferSubStreamFullscreen(prefer) }

        fun setKeepOffscreenTilesAlive(keep: Boolean) = viewModelScope.launch { userSettingsRepo.setKeepOffscreenTilesAlive(keep) }

        fun setThemeMode(mode: String) = viewModelScope.launch { userSettingsRepo.setThemeMode(mode) }

        fun setAccentColor(color: Long) = viewModelScope.launch { userSettingsRepo.setAccentColor(color) }

        fun setUseWallpaperColor(use: Boolean) = viewModelScope.launch { userSettingsRepo.setUseWallpaperColor(use) }

        fun setPaletteStyle(style: String) = viewModelScope.launch { userSettingsRepo.setPaletteStyle(style) }

        fun setAmoledBlack(enabled: Boolean) = viewModelScope.launch { userSettingsRepo.setAmoledBlack(enabled) }

        fun setAutoRefreshCameras(enabled: Boolean) = viewModelScope.launch { userSettingsRepo.setAutoRefreshCameras(enabled) }

        fun setCameraGridColumns(columns: Int) = viewModelScope.launch { userSettingsRepo.setCameraGridColumns(columns) }

        fun setHideEventImageInStream(hide: Boolean) = viewModelScope.launch { userSettingsRepo.setHideEventImageInStream(hide) }

        fun setAutoLandscapeOnStream(auto: Boolean) = viewModelScope.launch { userSettingsRepo.setAutoLandscapeOnStream(auto) }

        fun setRtspReconnectAttempts(attempts: Int) = viewModelScope.launch { userSettingsRepo.setRtspReconnectAttempts(attempts) }

        fun setRtspReconnectBaseDelaySeconds(seconds: Int) =
            viewModelScope.launch { userSettingsRepo.setRtspReconnectBaseDelaySeconds(seconds) }

        fun setAutoRefreshInterval(seconds: Int) = viewModelScope.launch { userSettingsRepo.setAutoRefreshInterval(seconds) }

        fun setEventPhotoPreference(pref: String) = viewModelScope.launch { userSettingsRepo.setEventPhotoPreference(pref) }

        fun setGridStreamType(option: String) = viewModelScope.launch { userSettingsRepo.setGridStreamType(option) }

        fun setFullscreenStreamType(option: String) = viewModelScope.launch { userSettingsRepo.setFullscreenStreamType(option) }

        fun setShowBoundingBoxes(show: Boolean) = viewModelScope.launch { userSettingsRepo.setShowBoundingBoxes(show) }

        fun setEventGridColumns(columns: Int) = viewModelScope.launch { userSettingsRepo.setEventGridColumns(columns) }

        fun setDateFormat(fmt: String) = viewModelScope.launch { userSettingsRepo.setDateFormat(fmt) }

        fun setContrastLevel(level: Int) = viewModelScope.launch { userSettingsRepo.setContrastLevel(level) }

        fun setCardCornerRadius(radius: Int) = viewModelScope.launch { userSettingsRepo.setCardCornerRadius(radius) }

        fun setCardBorderWidth(width: Int) = viewModelScope.launch { userSettingsRepo.setCardBorderWidth(width) }

        fun addCustomAccentColor(color: Long) =
            viewModelScope.launch {
                val current = state.value.customAccentColors
                if (color !in current) userSettingsRepo.setCustomAccentColors(current + color)
            }

        fun removeCustomAccentColor(color: Long) =
            viewModelScope.launch {
                userSettingsRepo.setCustomAccentColors(state.value.customAccentColors - color)
            }

        fun setShowLastImageWhileLoading(show: Boolean) = viewModelScope.launch { userSettingsRepo.setShowLastImageWhileLoading(show) }

        fun setRtspPort(port: Int) {
            viewModelScope.launch {
                val server = serverRepo.activeServer() ?: return@launch
                serverRepo.upsert(server.copy(rtspPort = port))
                client.invalidate(server.id)
            }
        }

        private val _connectionTest = MutableStateFlow<ConnectionTestResult?>(null)
        val connectionTest: StateFlow<ConnectionTestResult?> = _connectionTest.asStateFlow()

        /** Pings [serverId] via the existing config() endpoint and records round-trip latency. */
        fun testConnection(serverId: String) {
            viewModelScope.launch {
                val server = serverRepo.all().firstOrNull { it.id == serverId } ?: return@launch
                val start = System.currentTimeMillis()
                val api = client.apiFor(server, wifiMonitor.ssid.value)
                val result = safeApiCall { api.config() }
                val elapsed = System.currentTimeMillis() - start
                _connectionTest.value =
                    when (result) {
                        is ApiResult.Success -> ConnectionTestResult(serverId, true, elapsed, "Reachable")
                        is ApiResult.HttpError -> ConnectionTestResult(serverId, false, elapsed, "HTTP ${result.code}: ${result.message}")
                        is ApiResult.NetworkError -> ConnectionTestResult(serverId, false, null, result.cause.message ?: "Network error")
                        is ApiResult.ParseError -> ConnectionTestResult(serverId, false, elapsed, "Server returned an unexpected response")
                    }
            }
        }

        fun clearConnectionTest() {
            _connectionTest.value = null
        }

        /**
         * Same connectivity probe as [testConnection], but for an in-progress (unsaved)
         * [ServerForm] — lets the add/edit sheet's Test button work before the user hits Save.
         * The draft's own [ServerForm.id] (a fresh UUID, never persisted) doubles as a safe,
         * collision-free throwaway key: a password is written under it only long enough to run
         * one request (so Basic-auth-protected servers are actually exercised, same as a saved
         * server would be), then both the credential and the cached [FrigateClient] entry are
         * torn down in `finally` regardless of outcome — nothing about the draft survives the test.
         */
        fun testDraftConnection(form: ServerForm) {
            viewModelScope.launch {
                val draftServer = form.toDraftServer()
                if (form.password.isNotBlank()) {
                    val raw =
                        if (form.authMode == AuthMode.BASIC) {
                            "${form.username}:${form.password}"
                        } else {
                            form.password
                        }
                    credentialStore.setPassword(draftServer.id, raw)
                }
                try {
                    val start = System.currentTimeMillis()
                    val api = client.apiFor(draftServer, wifiMonitor.ssid.value)
                    val result = safeApiCall { api.config() }
                    val elapsed = System.currentTimeMillis() - start
                    _connectionTest.value =
                        when (result) {
                            is ApiResult.Success -> {
                                ConnectionTestResult(draftServer.id, true, elapsed, "Reachable")
                            }

                            is ApiResult.HttpError -> {
                                ConnectionTestResult(draftServer.id, false, elapsed, "HTTP ${result.code}: ${result.message}")
                            }

                            is ApiResult.NetworkError -> {
                                ConnectionTestResult(draftServer.id, false, null, result.cause.message ?: "Network error")
                            }

                            is ApiResult.ParseError -> {
                                ConnectionTestResult(draftServer.id, false, elapsed, "Server returned an unexpected response")
                            }
                        }
                } finally {
                    credentialStore.delete(draftServer.id)
                    client.invalidate(draftServer.id)
                }
            }
        }

        private val _stats = MutableStateFlow<ApiResult<kotlinx.serialization.json.JsonElement>?>(null)
        val stats: StateFlow<ApiResult<kotlinx.serialization.json.JsonElement>?> = _stats.asStateFlow()

        fun fetchStats() {
            viewModelScope.launch {
                _stats.value = repo.stats()
            }
        }
    }
