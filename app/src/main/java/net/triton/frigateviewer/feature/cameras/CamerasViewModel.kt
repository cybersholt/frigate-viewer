package net.triton.frigateviewer.feature.cameras

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.Server
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.data.UserSettingsRepository
import net.triton.frigateviewer.core.model.CameraConfig
import net.triton.frigateviewer.core.network.ApiResult
import net.triton.frigateviewer.core.network.WifiMonitor
import javax.inject.Inject

data class CamerasUiState(
    val loading: Boolean = false,
    val cameras: Map<String, CameraConfig> = emptyMap(),
    val errorMessage: String? = null,
    val noServerConfigured: Boolean = false,
    val activeServer: Server? = null,
    val effectiveBaseUrl: String? = null,
    val preferSubStream: Boolean = false,
    val go2rtcStreams: Set<String> = emptySet(),
    val gridColumns: Int = 2,
    val autoRefresh: Boolean = false,
    val autoRefreshInterval: Int = 3,
    val liveStreamOption: String = "webrtc",
    val showBoundingBoxes: Boolean = true,
    val hideEventImage: Boolean = false,
    val autoLandscapeOnStream: Boolean = false,
    val refreshTimestamp: Long = 0L,
)

@HiltViewModel
class CamerasViewModel
    @Inject
    constructor(
        private val repo: FrigateRepository,
        private val serverRepo: ServerRepository,
        private val userSettingsRepo: UserSettingsRepository,
        private val wifiMonitor: WifiMonitor,
    ) : ViewModel() {
        private val _state = MutableStateFlow(CamerasUiState())
        val state: StateFlow<CamerasUiState> = _state.asStateFlow()

        private var refreshJob: Job? = null
        private var isRefreshing = false

        init {
            viewModelScope.launch {
                combine(
                    serverRepo.activeServerId,
                    userSettingsRepo.preferSubStream,
                    userSettingsRepo.cameraGridColumns,
                    userSettingsRepo.autoRefreshCameras,
                    userSettingsRepo.autoRefreshInterval,
                ) { activeId, preferSub, grid, auto, interval ->
                    _state.value =
                        _state.value.copy(
                            preferSubStream = preferSub,
                            gridColumns = grid,
                            autoRefresh = auto,
                            autoRefreshInterval = interval,
                        )
                    activeId
                }.collectLatest {
                    refresh(forceCacheRefresh = true)
                    startOrStopAutoRefresh()
                }
            }

            viewModelScope.launch {
                combine(
                    userSettingsRepo.liveStreamOption,
                    userSettingsRepo.showBoundingBoxes,
                    userSettingsRepo.hideEventImageInStream,
                    userSettingsRepo.autoLandscapeOnStream,
                ) { option, showBoxes, hide, autoLandscape ->
                    _state.value =
                        _state.value.copy(
                            liveStreamOption = option,
                            showBoundingBoxes = showBoxes,
                            hideEventImage = hide,
                            autoLandscapeOnStream = autoLandscape,
                        )
                }.collectLatest { }
            }

            viewModelScope.launch {
                wifiMonitor.ssid.collect { ssid ->
                    val server = _state.value.activeServer ?: return@collect
                    _state.value = _state.value.copy(effectiveBaseUrl = server.effectiveBaseUrl(ssid))
                }
            }
        }

        private fun startOrStopAutoRefresh() {
            refreshJob?.cancel()
            if (_state.value.autoRefresh) {
                refreshJob =
                    viewModelScope.launch {
                        while (true) {
                            delay(_state.value.autoRefreshInterval * 1000L)
                            refresh(isSilent = true)
                        }
                    }
            }
        }

        fun refresh(
            isSilent: Boolean = false,
            forceCacheRefresh: Boolean = false,
        ) {
            if (isRefreshing) return
            isRefreshing = true

            viewModelScope.launch {
                try {
                    val server = serverRepo.activeServer()
                    if (server == null) {
                        _state.value = _state.value.copy(noServerConfigured = true, loading = false)
                        return@launch
                    }
                    val effectiveBaseUrl = server.effectiveBaseUrl(wifiMonitor.ssid.value)
                    if (!isSilent) {
                        _state.value =
                            _state.value.copy(
                                loading = true,
                                errorMessage = null,
                                activeServer = server,
                                effectiveBaseUrl = effectiveBaseUrl,
                            )
                    }

                    val streamsResult = repo.go2rtcStreams(forceRefresh = forceCacheRefresh)
                    val streams = if (streamsResult is ApiResult.Success) streamsResult.data.keys else emptySet()

                    when (val r = repo.config(forceRefresh = forceCacheRefresh)) {
                        is ApiResult.Success -> {
                            _state.value =
                                _state.value.copy(
                                    loading = false,
                                    cameras = r.data.cameras,
                                    activeServer = server,
                                    effectiveBaseUrl = effectiveBaseUrl,
                                    go2rtcStreams = streams,
                                    refreshTimestamp = System.currentTimeMillis(),
                                )
                        }

                        is ApiResult.HttpError -> {
                            if (!isSilent) {
                                _state.value =
                                    _state.value.copy(
                                        loading = false,
                                        errorMessage = "HTTP ${r.code}: ${r.message}",
                                        activeServer = server,
                                    )
                            }
                        }

                        is ApiResult.NetworkError -> {
                            if (!isSilent) {
                                _state.value =
                                    _state.value.copy(
                                        loading = false,
                                        errorMessage = "Network error: ${r.cause.message ?: "connection failed"}",
                                        activeServer = server,
                                    )
                            }
                        }

                        is ApiResult.ParseError -> {
                            if (!isSilent) {
                                _state.value =
                                    _state.value.copy(
                                        loading = false,
                                        errorMessage = "Server returned an unexpected response.",
                                        activeServer = server,
                                    )
                            }
                        }
                    }
                } finally {
                    isRefreshing = false
                }
            }
        }
    }
