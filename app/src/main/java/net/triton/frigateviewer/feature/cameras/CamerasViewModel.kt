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
import net.triton.frigateviewer.core.model.MotionActivity
import net.triton.frigateviewer.core.model.RecordingGap
import net.triton.frigateviewer.core.model.ReviewSegment
import net.triton.frigateviewer.core.network.ApiResult
import net.triton.frigateviewer.core.network.WifiMonitor
import javax.inject.Inject

/** How often [CamerasViewModel.setFocusedCamera]'s poll re-checks the focused camera's most recent event. */
private const val EVENT_POLL_INTERVAL_MS = 15_000L

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
    /** Recent-events side panel in fullscreen landscape. Off by default; toggled from fullscreen's overflow menu. */
    val showLiveEventsPanel: Boolean = false,
    /** Developer Options: Frigate-style telemetry overlay on live tiles. */
    val showStreamStats: Boolean = false,
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
    /**
     * Cameras with a currently-open (endTime == null) event, per the periodic check in
     * [CamerasViewModel.pollFocusedCameraEvent] — today that's only ever the fullscreen-focused
     * camera (see [CamerasViewModel.setFocusedCamera]), not every grid tile. Drives the "solid
     * blue" LiveIndicatorDot state (#20).
     */
    val activeEventCameraNames: Set<String> = emptySet(),
    /** Recent-events side panel (#16/#17) — list mode data for whichever camera has the panel open. */
    val sidePanelEvents: List<FrigateEvent> = emptyList(),
    val sidePanelEventsLoading: Boolean = false,
    /** Recent-events side panel — timeline mode data, fetched lazily on first switch to Timeline. */
    val sidePanelReviewSegments: List<ReviewSegment> = emptyList(),
    val sidePanelRecordingGaps: List<RecordingGap> = emptyList(),
    val sidePanelMotionActivity: List<MotionActivity> = emptyList(),
    val sidePanelTimelineLoading: Boolean = false,
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

        // Navigating to another tab keeps this ViewModel (and its StateFlow) alive via
        // Navigation-Compose's saveState/restoreState, so onCleared() never fires. Without this
        // flag, startOrStopAutoRefresh()'s loop would keep polling forever regardless of which
        // screen is actually visible. Screen calls setScreenVisible from a DisposableEffect.
        private var screenVisible = true

        private var focusedCamera: String? = null
        private var eventPollJob: Job? = null

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
                combine<Any?, Unit>(
                    userSettingsRepo.autoLandscapeOnStream,
                    userSettingsRepo.cameraStreamOverrides,
                    userSettingsRepo.showLastImageWhileLoading,
                    userSettingsRepo.rtspReconnectAttempts,
                    userSettingsRepo.rtspReconnectBaseDelaySeconds,
                    userSettingsRepo.showLiveEventsPanel,
                    userSettingsRepo.showStreamStats,
                ) { args ->
                    @Suppress("UNCHECKED_CAST")
                    _state.value =
                        _state.value.copy(
                            autoLandscapeOnStream = args[0] as Boolean,
                            cameraStreamOverrides = args[1] as Map<String, String>,
                            showLastImageWhileLoading = args[2] as Boolean,
                            rtspReconnectAttempts = args[3] as Int,
                            rtspReconnectBaseDelaySeconds = args[4] as Int,
                            showLiveEventsPanel = args[5] as Boolean,
                            showStreamStats = args[6] as Boolean,
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

        /** Called from CamerasScreen's DisposableEffect so polling stops when this tab isn't visible. */
        fun setScreenVisible(visible: Boolean) {
            if (screenVisible == visible) return
            screenVisible = visible
            startOrStopAutoRefresh()
        }

        /**
         * Called from CamerasScreen whenever the fullscreen-focused camera changes (including to
         * null on exit) — drives [pollFocusedCameraEvent] below. Scoped to the one camera actually
         * being viewed in detail, not every grid tile, to avoid repeating this app's past
         * auto-refresh traffic-leak mistake (see project_state.md, 2026-07-10 session) by polling
         * cameras nobody's looking at.
         */
        fun setFocusedCamera(name: String?) {
            if (focusedCamera == name) return
            focusedCamera = name
            eventPollJob?.cancel()
            if (name == null) {
                _state.value = _state.value.copy(activeEventCameraNames = emptySet())
                return
            }
            eventPollJob =
                viewModelScope.launch {
                    while (true) {
                        pollFocusedCameraEvent(name)
                        // TODO(#20 follow-up): expose this interval in Developer Options once a
                        // polling-rate control exists there, instead of the hardcoded constant
                        // below — same idea as autoRefreshInterval, but for this poll specifically.
                        delay(EVENT_POLL_INTERVAL_MS)
                    }
                }
        }

        private suspend fun pollFocusedCameraEvent(cameraName: String) {
            if (!screenVisible) return
            when (val r = repo.events(camera = cameraName, limit = 1)) {
                is ApiResult.Success -> {
                    val open = r.data.firstOrNull()?.endTime == null && r.data.isNotEmpty()
                    _state.value =
                        _state.value.copy(
                            activeEventCameraNames = if (open) setOf(cameraName) else emptySet(),
                        )
                }

                else -> {}
            }
        }

        private fun startOrStopAutoRefresh() {
            refreshJob?.cancel()
            if (_state.value.autoRefresh && screenVisible) {
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

        fun setShowLiveEventsPanel(show: Boolean) {
            viewModelScope.launch {
                userSettingsRepo.setShowLiveEventsPanel(show)
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

        private var sidePanelCameraName: String? = null

        /** Fetch a recent-events list for [cameraName]'s live-view side panel (#16), List mode. */
        fun loadSidePanelEvents(cameraName: String) {
            if (sidePanelCameraName == cameraName && _state.value.sidePanelEvents.isNotEmpty()) return
            sidePanelCameraName = cameraName
            viewModelScope.launch {
                _state.value = _state.value.copy(sidePanelEventsLoading = true)
                when (val r = repo.events(camera = cameraName, limit = 25)) {
                    is ApiResult.Success -> {
                        _state.value =
                            _state.value.copy(sidePanelEvents = r.data, sidePanelEventsLoading = false)
                    }

                    else -> {
                        _state.value = _state.value.copy(sidePanelEventsLoading = false)
                    }
                }
            }
        }

        /** Fetch review/recording-gap/motion data for [cameraName]'s side panel (#17), Timeline mode. */
        fun loadSidePanelTimeline(
            cameraName: String,
            timeRangeHours: Float,
        ) {
            viewModelScope.launch {
                _state.value = _state.value.copy(sidePanelTimelineLoading = true)
                val nowSec = System.currentTimeMillis() / 1000.0
                val afterSec = nowSec - timeRangeHours * 3_600.0
                val reviewResult = repo.review(cameras = cameraName, after = afterSec, before = nowSec)
                val gapsResult = repo.recordingGaps(cameras = cameraName, after = afterSec, before = nowSec)
                val motionResult = repo.motionActivity(cameras = cameraName, after = afterSec, before = nowSec)
                _state.value =
                    _state.value.copy(
                        sidePanelReviewSegments = (reviewResult as? ApiResult.Success)?.data ?: emptyList(),
                        sidePanelRecordingGaps = (gapsResult as? ApiResult.Success)?.data ?: emptyList(),
                        sidePanelMotionActivity = (motionResult as? ApiResult.Success)?.data ?: emptyList(),
                        sidePanelTimelineLoading = false,
                    )
            }
        }

        /** Reset the side panel's cached data — called when the fullscreen-focused camera changes/closes. */
        fun clearSidePanel() {
            sidePanelCameraName = null
            _state.value =
                _state.value.copy(
                    sidePanelEvents = emptyList(),
                    sidePanelReviewSegments = emptyList(),
                    sidePanelRecordingGaps = emptyList(),
                    sidePanelMotionActivity = emptyList(),
                )
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
