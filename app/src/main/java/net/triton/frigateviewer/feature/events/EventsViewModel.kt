package net.triton.frigateviewer.feature.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.data.UserSettingsRepository
import net.triton.frigateviewer.core.db.CachedEvent
import net.triton.frigateviewer.core.db.EventDao
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.model.RecordingGap
import net.triton.frigateviewer.core.model.ReviewSegment
import net.triton.frigateviewer.core.network.ApiResult
import net.triton.frigateviewer.core.network.WifiMonitor
import javax.inject.Inject

data class EventsUiState(
    val loading: Boolean = false,
    val events: List<FrigateEvent> = emptyList(),
    val error: String? = null,
    val baseUrl: String? = null,
    val offlineCache: Boolean = false,
    val autoRefresh: Boolean = false,
    val autoRefreshInterval: Int = 3,
    val eventPhotoPreference: String = "snapshot",
    val selectedCameras: Set<String> = emptySet(),
    val selectedLabels: Set<String> = emptySet(),
    val selectedZones: Set<String> = emptySet(),
    val retainedOnly: Boolean = false,
    val availableCameras: List<String> = emptyList(),
    val availableLabels: List<String> = emptyList(),
    val availableZones: List<String> = emptyList(),
    val eventGridColumns: Int = 1,
    val dateFormat: String = "descriptive",
    /** Current position of the timeline scrubber (ms since epoch). Defaults to now. */
    val scrubberTimeMs: Long = System.currentTimeMillis(),
    /** How many hours the timeline displays (zoom level). Range 1–720h (30 days). */
    val timeRangeHours: Float = 24f,
    /** Severity-classified review segments backing the TimelinePanel activity waveform. */
    val reviewSegments: List<ReviewSegment> = emptyList(),
    /** Recording-gap ranges backing the TimelinePanel's darkened "no footage" bands. */
    val recordingGaps: List<RecordingGap> = emptyList(),
)

@HiltViewModel
class EventsViewModel
    @Inject
    constructor(
        private val repo: FrigateRepository,
        private val serverRepo: ServerRepository,
        private val userSettingsRepo: UserSettingsRepository,
        private val wifiMonitor: WifiMonitor,
        private val dao: EventDao,
    ) : ViewModel() {
        private val _state = MutableStateFlow(EventsUiState())
        val state = _state.asStateFlow()

        private var allEvents: List<FrigateEvent> = emptyList()
        private var refreshJob: Job? = null
        private var fetchedWindowDays = 7

        /** Single source of truth for camera display order — same list the Cameras grid uses. */
        private var cameraOrder: List<String> = emptyList()

        init {
            viewModelScope.launch {
                combine(
                    serverRepo.activeServerId,
                    userSettingsRepo.autoRefreshCameras,
                    userSettingsRepo.autoRefreshInterval,
                    userSettingsRepo.eventPhotoPreference,
                    userSettingsRepo.eventGridColumns,
                ) { activeId, auto, interval, pref, gridCols ->
                    _state.value =
                        _state.value.copy(
                            autoRefresh = auto,
                            autoRefreshInterval = interval,
                            eventPhotoPreference = pref,
                            eventGridColumns = gridCols,
                        )
                    activeId
                }.collectLatest {
                    refresh()
                    startOrStopAutoRefresh()
                }
            }

            viewModelScope.launch {
                userSettingsRepo.cameraOrder.collect { order ->
                    cameraOrder = order
                    if (allEvents.isNotEmpty()) updateAvailableFilters(allEvents)
                }
            }

            viewModelScope.launch {
                wifiMonitor.ssid.collect { ssid ->
                    val server = serverRepo.activeServer() ?: return@collect
                    _state.value = _state.value.copy(baseUrl = server.effectiveBaseUrl(ssid))
                }
            }

            viewModelScope.launch {
                userSettingsRepo.dateFormat.collect { fmt ->
                    _state.value = _state.value.copy(dateFormat = fmt)
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

        fun toggleCamera(camera: String) {
            val current = _state.value.selectedCameras
            _state.value =
                _state.value.copy(
                    selectedCameras = if (camera in current) current - camera else current + camera,
                )
            applyFilters()
            loadTimelineData()
        }

        fun toggleLabel(label: String) {
            val current = _state.value.selectedLabels
            _state.value =
                _state.value.copy(
                    selectedLabels = if (label in current) current - label else current + label,
                )
            applyFilters()
        }

        fun toggleZone(zone: String) {
            val current = _state.value.selectedZones
            _state.value =
                _state.value.copy(
                    selectedZones = if (zone in current) current - zone else current + zone,
                )
            applyFilters()
        }

        fun setRetainedOnly(only: Boolean) {
            _state.value = _state.value.copy(retainedOnly = only)
            applyFilters()
        }

        fun clearAllFilters() {
            _state.value =
                _state.value.copy(
                    selectedCameras = emptySet(),
                    selectedLabels = emptySet(),
                    selectedZones = emptySet(),
                    retainedOnly = false,
                )
            applyFilters()
        }

        /** Apply pre-set filters from deep-link / camera-tile swipe navigation. */
        fun initFilters(
            camera: String?,
            label: String?,
            zone: String?,
        ) {
            if (camera == null && label == null && zone == null) return
            _state.value =
                _state.value.copy(
                    selectedCameras = if (camera != null) setOf(camera) else emptySet(),
                    selectedLabels = if (label != null) setOf(label) else emptySet(),
                    selectedZones = if (zone != null) setOf(zone) else emptySet(),
                )
            applyFilters()
        }

        /** Move the timeline scrubber to [timeMs]. */
        fun setScrubberTime(timeMs: Long) {
            _state.value = _state.value.copy(scrubberTimeMs = timeMs)
        }

        /** Zoom the timeline in (factor < 1) or out (factor > 1). */
        fun zoomTimeline(factor: Float) {
            val newRange = (_state.value.timeRangeHours * factor).coerceIn(1f, 720f) // up to 30 days
            _state.value = _state.value.copy(timeRangeHours = newRange)
            val neededDays = (newRange / 24).toInt().coerceAtLeast(7)
            if (neededDays > fetchedWindowDays) {
                fetchedWindowDays = neededDays.coerceAtMost(30)
                refresh(isSilent = true)
            }
            scheduleTimelineDataRefresh()
        }

        private var timelineDataJob: Job? = null

        /**
         * Fetches severity-classified review segments + recording gaps for the currently visible
         * timeline window, across whichever cameras are selected (all cameras if none selected).
         * Mirrors [net.triton.frigateviewer.feature.events.EventDetail]'s identical pattern.
         */
        fun loadTimelineData() {
            val s = _state.value
            val cameras = s.selectedCameras.ifEmpty { s.availableCameras.toSet() }
            if (cameras.isEmpty()) return
            val camerasParam = cameras.joinToString(",")
            val rangeMs = (s.timeRangeHours * 3_600_000L).toLong()
            val nowMs = System.currentTimeMillis()
            val afterSec = (nowMs - rangeMs) / 1000.0
            val beforeSec = nowMs / 1000.0
            viewModelScope.launch {
                when (val r = repo.review(camerasParam, after = afterSec, before = beforeSec)) {
                    is ApiResult.Success -> {
                        _state.value = _state.value.copy(reviewSegments = r.data)
                    }

                    else -> {}
                }
            }
            viewModelScope.launch {
                val scaleSeconds = (rangeMs / 1000L / 300L).toInt().coerceIn(1, 3600)
                when (
                    val r =
                        repo.recordingGaps(camerasParam, after = afterSec, before = beforeSec, scale = scaleSeconds)
                ) {
                    is ApiResult.Success -> {
                        _state.value = _state.value.copy(recordingGaps = r.data)
                    }

                    else -> {}
                }
            }
        }

        /** Debounced [loadTimelineData] for continuous zoom gestures. */
        private fun scheduleTimelineDataRefresh() {
            timelineDataJob?.cancel()
            timelineDataJob =
                viewModelScope.launch {
                    delay(400)
                    loadTimelineData()
                }
        }

        private fun applyFilters() {
            _state.value = _state.value.copy(events = filterEvents(allEvents))
        }

        fun refresh(isSilent: Boolean = false) {
            viewModelScope.launch {
                val server = serverRepo.activeServer()
                val baseUrl = server?.effectiveBaseUrl(wifiMonitor.ssid.value)

                if (server != null && !isSilent) {
                    val cached = dao.list(server.id).map { it.toDomain() }
                    if (cached.isNotEmpty()) {
                        allEvents = cached
                        updateAvailableFilters(cached)
                        _state.value =
                            _state.value.copy(
                                events = filterEvents(cached),
                                baseUrl = baseUrl,
                                offlineCache = true,
                            )
                    }
                }
                if (!isSilent) _state.value = _state.value.copy(loading = true, error = null, baseUrl = baseUrl)

                val windowSecs = (System.currentTimeMillis() / 1000.0) - fetchedWindowDays * 24 * 3600
                when (val r = repo.events(limit = 3000, after = windowSecs)) {
                    is ApiResult.Success -> {
                        allEvents = r.data
                        updateAvailableFilters(r.data)
                        _state.value =
                            _state.value.copy(
                                events = filterEvents(r.data),
                                baseUrl = baseUrl,
                                loading = false,
                                offlineCache = false,
                            )
                        if (server != null) dao.upsertAll(r.data.map { it.toCached(server.id) })
                        loadTimelineData()
                    }

                    is ApiResult.HttpError -> {
                        _state.value = _state.value.copy(loading = false, error = "HTTP ${r.code}")
                    }

                    is ApiResult.NetworkError -> {
                        _state.value = _state.value.copy(loading = false, error = r.cause.message ?: "Network error")
                    }

                    is ApiResult.ParseError -> {
                        _state.value = _state.value.copy(loading = false, error = "Bad response from server")
                    }
                }
            }
        }

        private fun updateAvailableFilters(events: List<FrigateEvent>) {
            val seen = events.map { it.camera }.distinct()
            val cameras =
                if (cameraOrder.isEmpty()) {
                    seen.sorted()
                } else {
                    val inOrder = cameraOrder.filter { it in seen }
                    val notInOrder = seen.filter { it !in cameraOrder }.sorted()
                    inOrder + notInOrder
                }
            val labels = events.map { it.label }.distinct().sorted()
            val zones = events.flatMap { it.zones }.distinct().sorted()
            _state.value =
                _state.value.copy(
                    availableCameras = cameras,
                    availableLabels = labels,
                    availableZones = zones,
                )
        }

        private fun filterEvents(events: List<FrigateEvent>): List<FrigateEvent> {
            val s = _state.value
            return events.filter { ev ->
                (s.selectedCameras.isEmpty() || ev.camera in s.selectedCameras) &&
                    (s.selectedLabels.isEmpty() || ev.label in s.selectedLabels) &&
                    (s.selectedZones.isEmpty() || ev.zones.any { it in s.selectedZones }) &&
                    (!s.retainedOnly || ev.retained)
            }
        }

        fun delete(id: String) {
            viewModelScope.launch {
                when (repo.deleteEvent(id)) {
                    is ApiResult.Success -> {
                        val server = serverRepo.activeServer()
                        if (server != null) dao.delete(server.id, id)
                        allEvents = allEvents.filterNot { it.id == id }
                        _state.value = _state.value.copy(events = _state.value.events.filterNot { it.id == id })
                    }

                    else -> {}
                }
            }
        }

        fun toggleRetain(ev: FrigateEvent) {
            viewModelScope.launch {
                when (repo.retainEvent(ev.id, retain = !ev.retained)) {
                    is ApiResult.Success -> {
                        val updated = allEvents.map { if (it.id == ev.id) it.copy(retained = !it.retained) else it }
                        allEvents = updated
                        _state.value = _state.value.copy(events = filterEvents(updated))
                    }

                    else -> {}
                }
            }
        }
    }

private fun CachedEvent.toDomain() =
    FrigateEvent(
        id = eventId,
        camera = camera,
        label = label,
        startTime = startTime,
        endTime = endTime,
        hasSnapshot = hasSnapshot,
        hasClip = hasClip,
        topScore = topScore,
        retained = retained,
    )

private fun FrigateEvent.toCached(serverId: String) =
    CachedEvent(
        serverId = serverId,
        eventId = id,
        camera = camera,
        label = label,
        startTime = startTime,
        endTime = endTime,
        hasSnapshot = hasSnapshot,
        hasClip = hasClip,
        retained = retained,
        topScore = topScore,
    )
