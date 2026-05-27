package net.triton.frigateviewer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.data.CredentialStore
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.Server
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.data.UserSettingsRepository
import net.triton.frigateviewer.core.network.AuthMode
import net.triton.frigateviewer.core.network.FrigateClient
import net.triton.frigateviewer.core.network.WifiMonitor
import net.triton.frigateviewer.notification.ServiceController
import java.util.UUID
import javax.inject.Inject

data class ServerForm(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: String = "https",
    val host: String = "",
    val port: String = "8971",
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
    val preferSubStream: Boolean = false,
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
    val liveStreamOption: String = "webrtc",
    val showBoundingBoxes: Boolean = true,
    val eventGridColumns: Int = 1,
    val dateFormat: String = "descriptive",
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

        val state: kotlinx.coroutines.flow.StateFlow<SettingsUiState> =
            combine(
                serverRepo.servers,
                serverRepo.activeServerId,
                userSettingsRepo.preferSubStream,
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
                userSettingsRepo.liveStreamOption,
                userSettingsRepo.showBoundingBoxes,
                userSettingsRepo.eventGridColumns,
                userSettingsRepo.dateFormat,
            ) { args ->
                @Suppress("UNCHECKED_CAST")
                SettingsUiState(
                    servers = args[0] as List<Server>,
                    activeId = args[1] as String?,
                    preferSubStream = args[2] as Boolean,
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
                    liveStreamOption = args[14] as String,
                    showBoundingBoxes = args[15] as Boolean,
                    eventGridColumns = args[16] as Int,
                    dateFormat = args[17] as String,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

        @Suppress("unused")
        private val _testResult = MutableStateFlow<String?>(null)

        fun saveServer(form: ServerForm) {
            viewModelScope.launch {
                val port = form.port.toIntOrNull()
                val server =
                    Server(
                        id = form.id,
                        name = form.name.ifBlank { form.host },
                        protocol = form.protocol,
                        host = form.host.trim(),
                        port = port,
                        basePath = form.basePath.trim(),
                        authMode = form.authMode,
                        username = form.username.ifBlank { null },
                        allowUntrusted = form.allowUntrusted,
                        rtspPort = form.rtspPort.toIntOrNull() ?: 8554,
                        rtspHost = form.rtspHost.trim().ifBlank { null },
                        localNetworkUrl = form.localNetworkUrl.trim().ifBlank { null },
                        localNetworkSsids = form.localNetworkSsids,
                    )
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

        fun setPreferSubStream(prefer: Boolean) = viewModelScope.launch { userSettingsRepo.setPreferSubStream(prefer) }

        fun setThemeMode(mode: String) = viewModelScope.launch { userSettingsRepo.setThemeMode(mode) }

        fun setAccentColor(color: Long) = viewModelScope.launch { userSettingsRepo.setAccentColor(color) }

        fun setUseWallpaperColor(use: Boolean) = viewModelScope.launch { userSettingsRepo.setUseWallpaperColor(use) }

        fun setPaletteStyle(style: String) = viewModelScope.launch { userSettingsRepo.setPaletteStyle(style) }

        fun setAmoledBlack(enabled: Boolean) = viewModelScope.launch { userSettingsRepo.setAmoledBlack(enabled) }

        fun setAutoRefreshCameras(enabled: Boolean) = viewModelScope.launch { userSettingsRepo.setAutoRefreshCameras(enabled) }

        fun setCameraGridColumns(columns: Int) = viewModelScope.launch { userSettingsRepo.setCameraGridColumns(columns) }

        fun setHideEventImageInStream(hide: Boolean) = viewModelScope.launch { userSettingsRepo.setHideEventImageInStream(hide) }

        fun setAutoLandscapeOnStream(auto: Boolean) = viewModelScope.launch { userSettingsRepo.setAutoLandscapeOnStream(auto) }

        fun setAutoRefreshInterval(seconds: Int) = viewModelScope.launch { userSettingsRepo.setAutoRefreshInterval(seconds) }

        fun setEventPhotoPreference(pref: String) = viewModelScope.launch { userSettingsRepo.setEventPhotoPreference(pref) }

        fun setLiveStreamOption(option: String) = viewModelScope.launch { userSettingsRepo.setLiveStreamOption(option) }

        fun setShowBoundingBoxes(show: Boolean) = viewModelScope.launch { userSettingsRepo.setShowBoundingBoxes(show) }

        fun setEventGridColumns(columns: Int) = viewModelScope.launch { userSettingsRepo.setEventGridColumns(columns) }

        fun setDateFormat(fmt: String) = viewModelScope.launch { userSettingsRepo.setDateFormat(fmt) }

        fun setRtspPort(port: Int) {
            viewModelScope.launch {
                val server = serverRepo.activeServer() ?: return@launch
                serverRepo.upsert(server.copy(rtspPort = port))
                client.invalidate(server.id)
            }
        }
    }
