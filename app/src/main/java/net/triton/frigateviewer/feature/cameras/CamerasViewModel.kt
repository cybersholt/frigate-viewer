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
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.network.ApiResult
import net.triton.frigateviewer.core.network.WifiMonitor
import javax.inject.Inject

data class CamerasUiState(
    val loading: Boolean = false,
    val cameras: Map<String, CameraConfig> = emptyMap(),
    /** Ordered, visible camera names — derived from cameras + cameraOrder + hiddenCameras. */
    val displayedCameras: List<String> = emptyList(),
    val errorMessage: String? = null,
    val noServerConfigured: Boolean = false,
    val activeServer: Server? = null,
    val effectiveBaseUrl: String? = null,
    /** Currently-connected Wi-Fi SSID, or null off Wi-Fi. Used to gate LAN-only RTSP. */
    val currentSsid: String? = null,
    val preferSubStreamGrid: Boolean = true,
    val preferSubStreamFullscreen: Boolean = false,
    val go2rtcStreams: Set<String> = emptySet(),
    val gridColumns: Int = 2,
    val autoRefresh: Boolean = false,
    val autoRefreshInterval: Int = 3,
    val gridStreamType: String = "snapshot",
    val fullscreenStreamType: String = "webrtc",
    val keepOffscreenTilesAlive: Boolean = false,
    val subStreamFallbacks: Map<String, Boolean> = emptyMap(),
    /** Per-camera live-mode overrides; a camera absent here uses [gridStreamType]/[fullscreenStreamType]. */
    val cameraStreamOverrides: Map<String, String> = emptyMap(),
    val showBoundingBoxes: Boolean = true,
    val hideEventImage: Boolean = false,
    val autoLandscapeOnStream: Boolean = false,
    val showLastImageWhileLoading: Boolean = true,
    val rtspReconnectAttempts: Int = 2,
    val rtspReconnectBaseDelaySeconds: Int = 2,
    val refreshTimestamp: Long = 0L,
    /** Persisted camera display order (empty = follow API order). */
    val cameraOrder: List<String> = emptyList(),
    /** Camera names hidden from the grid. */
    val hiddenCameras: Set<String> = emptySet(),
    /** Whether swipe gesture panels are enabled on tiles. */
    val showSwipeActions: Boolean = true,
    /** Camera names from the most recent successful load — used for skeleton backgrounds. */
    val knownCameraNames: List<String> = emptyList(),
    /** Global object-tracking labels from config (fallback when camera has no per-camera list). */
    val globalTrackedObjects: List<String> = emptyList(),
    /** Most recently fetched event per camera (populated lazily by swipe-left gesture). */
    val recentEvents: Map<String, FrigateEvent?> = emptyMap(),
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
            // Load persisted camera-management prefs and last-known names on startup.
            viewModelScope.launch {
                combine(
                    userSettingsRepo.cameraOrder,
                    userSettingsRepo.hiddenCameras,
                    userSettingsRepo.showCameraSwipeActions,
                    userSettingsRepo.lastKnownCameraNames,
                ) { order, hidden, swipe, known ->
                    _state.value =
                        _state.value.copy(
                            cameraOrder = order,
                            hiddenCameras = hidden,
                            showSwipeActions = swipe,
                            knownCameraNames = known,
                            displayedCameras = buildDisplayedCameras(_state.value.cameras, order, hidden),
                        )
                }.collectLatest { }
            }

            viewModelScope.launch {
                combine(
                    serverRepo.activeServerId,
                    userSettingsRepo.preferSubStreamGrid,
                    userSettingsRepo.cameraGridColumns,
                    userSettingsRepo.autoRefreshCameras,
                    userSettingsRepo.autoRefreshInterval,
                ) { activeId, preferSub, grid, auto, interval ->
                    _state.value =
                        _state.value.copy(
                            preferSubStreamGrid = preferSub,
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
                combine<Any?, Unit>(
                    userSettingsRepo.gridStreamType,
                    userSettingsRepo.fullscreenStreamType,
                    userSettingsRepo.preferSubStreamFullscreen,
                    userSettingsRepo.keepOffscreenTilesAlive,
                    userSettingsRepo.showBoundingBoxes,
                    userSettingsRepo.hideEventImageInStream,
                ) { args ->
                    _state.value =
                        _state.value.copy(
                            gridStreamType = args[0] as String,
                            fullscreenStreamType = args[1] as String,
                            preferSubStreamFullscreen = args[2] as Boolean,
                            keepOffscreenTilesAlive = args[3] as Boolean,
                            showBoundingBoxes = args[4] as Boolean,
                            hideEventImage = args[5] as Boolean,
                        )
                }.collectLatest { }
            }

            viewModelScope.launch {
                combine(
                    userSettingsRepo.autoLandscapeOnStream,
                    userSettingsRepo.cameraStreamOverrides,
                    userSettingsRepo.showLastImageWhileLoading,
                    userSettingsRepo.rtspReconnectAttempts,
                    userSettingsRepo.rtspReconnectBaseDelaySeconds,
                ) { autoLandscape, overrides, showLastImage, reconnectAttempts, reconnectDelay ->
                    _state.value =
                        _state.value.copy(
                            autoLandscapeOnStream = autoLandscape,
                            cameraStreamOverrides = overrides,
                            showLastImageWhileLoading = showLastImage,
                            rtspReconnectAttempts = reconnectAttempts,
                            rtspReconnectBaseDelaySeconds = reconnectDelay,
                        )
                }.collectLatest { }
            }

            viewModelScope.launch {
                wifiMonitor.ssid.collect { ssid ->
                    val server = _state.value.activeServer
                    _state.value =
                        _state.value.copy(
                            currentSsid = ssid,
                            effectiveBaseUrl = server?.effectiveBaseUrl(ssid) ?: _state.value.effectiveBaseUrl,
                        )
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
                    val effectiveBaseUrl = server.effectiveBaseUrl(_state.value.currentSsid)
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
                            val cameras = r.data.cameras
                            val globalObjects = r.data.objects?.track ?: emptyList()
                            val s = _state.value
                            val displayed = buildDisplayedCameras(cameras, s.cameraOrder, s.hiddenCameras)
                            _state.value =
                                s.copy(
                                    loading = false,
                                    cameras = cameras,
                                    displayedCameras = displayed,
                                    activeServer = server,
                                    effectiveBaseUrl = effectiveBaseUrl,
                                    go2rtcStreams = streams,
                                    refreshTimestamp = System.currentTimeMillis(),
                                    globalTrackedObjects = globalObjects,
                                )
                            // Persist names for next cold-start skeleton
                            val names = cameras.keys.toList()
                            if (names != s.knownCameraNames) {
                                userSettingsRepo.setLastKnownCameraNames(names)
                                _state.value = _state.value.copy(knownCameraNames = names)
                            }
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

        /** Persist a new camera display order and apply it immediately. */
        fun saveCameraOrder(ordered: List<String>) {
            viewModelScope.launch {
                userSettingsRepo.setCameraOrder(ordered)
                _state.value =
                    _state.value.copy(
                        cameraOrder = ordered,
                        displayedCameras = buildDisplayedCameras(_state.value.cameras, ordered, _state.value.hiddenCameras),
                    )
            }
        }

        /** Set (or, when [mode] is null, clear) [cameraName]'s live-mode override. */
        fun setCameraStreamOverride(
            cameraName: String,
            mode: String?,
        ) {
            viewModelScope.launch {
                userSettingsRepo.setCameraStreamOverride(cameraName, mode)
            }
        }

        /** Toggle a camera's hidden status and persist. */
        fun toggleHideCamera(name: String) {
            viewModelScope.launch {
                val current = _state.value.hiddenCameras
                val updated = if (name in current) current - name else current + name
                userSettingsRepo.setHiddenCameras(updated)
                _state.value =
                    _state.value.copy(
                        hiddenCameras = updated,
                        displayedCameras =
                            buildDisplayedCameras(_state.value.cameras, _state.value.cameraOrder, updated),
                    )
            }
        }

        /** Fetch the most recent event for [cameraName] (for swipe-left quick-view). */
        fun fetchRecentEvent(cameraName: String) {
            // Don't re-fetch if we already have a result
            if (_state.value.recentEvents.containsKey(cameraName)) return
            viewModelScope.launch {
                // Optimistic: set null so the UI shows a spinner
                _state.value = _state.value.copy(recentEvents = _state.value.recentEvents + (cameraName to null))
                when (val r = repo.events(camera = cameraName, limit = 1)) {
                    is ApiResult.Success -> {
                        _state.value =
                            _state.value.copy(
                                recentEvents = _state.value.recentEvents + (cameraName to r.data.firstOrNull()),
                            )
                    }

                    else -> {
                        // Remove placeholder so a retry is possible
                        _state.value =
                            _state.value.copy(
                                recentEvents = _state.value.recentEvents - cameraName,
                            )
                    }
                }
            }
        }

        /** Clear a cached recent-event so the next swipe refetches. */
        fun clearRecentEvent(cameraName: String) {
            _state.value = _state.value.copy(recentEvents = _state.value.recentEvents - cameraName)
        }

        /**
         * Returns the labels to offer for quick-filter buttons on [cameraName].
         * Uses per-camera track list, falls back to global, falls back to common defaults.
         */
        fun labelsForCamera(cameraName: String): List<String> {
            val perCamera =
                _state.value.cameras[cameraName]
                    ?.objects
                    ?.track ?: emptyList()
            if (perCamera.isNotEmpty()) return perCamera
            val global = _state.value.globalTrackedObjects
            if (global.isNotEmpty()) return global
            return listOf("person", "car", "bicycle", "dog", "cat")
        }

        /** Returns zone names configured for [cameraName]. */
        fun zonesForCamera(cameraName: String): List<String> =
            _state.value.cameras[cameraName]
                ?.zones
                ?.keys
                ?.sorted() ?: emptyList()

        /** Record that a camera's sub-stream failed, falling back to the main stream for this session. */
        fun markSubStreamFallback(cameraName: String) {
            _state.value =
                _state.value.copy(
                    subStreamFallbacks = _state.value.subStreamFallbacks + (cameraName to true),
                )
        }

        // --- helpers ---

        private fun buildDisplayedCameras(
            cameras: Map<String, CameraConfig>,
            order: List<String>,
            hidden: Set<String>,
        ): List<String> {
            val allNames = cameras.keys
            val sorted =
                if (order.isEmpty()) {
                    allNames.sorted()
                } else {
                    allNames.sortedWith(
                        Comparator { a, b ->
                            val ia = order.indexOf(a).let { if (it < 0) Int.MAX_VALUE else it }
                            val ib = order.indexOf(b).let { if (it < 0) Int.MAX_VALUE else it }
                            ia.compareTo(ib)
                        },
                    )
                }
            return sorted.filter { it !in hidden }
        }
    }
