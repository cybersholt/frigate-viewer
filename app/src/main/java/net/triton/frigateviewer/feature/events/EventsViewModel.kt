package net.triton.frigateviewer.feature.events

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.data.UserSettingsRepository
import net.triton.frigateviewer.core.db.CachedEvent
import net.triton.frigateviewer.core.db.EventDao
import net.triton.frigateviewer.core.media.extractFrame
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.model.MotionActivity
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
    /** Fine-grained motion waveform (`api/review/activity/motion`) backing bar intensity. */
    val motionActivity: List<MotionActivity> = emptyList(),
    /** Time under the finger while actively touching the timeline strip; null when not touching. */
    val scrubPreviewTimeMs: Long? = null,
    /** Still frame extracted locally from the cached preview clip at [scrubPreviewTimeMs]. */
    val scrubPreviewBitmap: Bitmap? = null,
)

private data class RefreshConfig(
    val activeServerId: String?,
    val autoRefresh: Boolean,
    val autoRefreshInterval: Int,
    val eventPhotoPreference: String,
    val eventGridColumns: Int,
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

        // Navigating to another tab keeps this ViewModel (and its StateFlow) alive via
        // Navigation-Compose's saveState/restoreState, so onCleared() never fires. Without this
        // flag, startOrStopAutoRefresh()'s loop would keep polling forever regardless of which
        // screen is actually visible. Screen calls setScreenVisible from a DisposableEffect.
        private var screenVisible = true

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
                    // Return the new settings as a bundle; don't update _state.value inside the
                    // transform block, as it can cause excessive recompositions/loops.
                    RefreshConfig(activeId, auto, interval, pref, gridCols)
                }.collectLatest { config ->
                    _state.value =
                        _state.value.copy(
                            autoRefresh = config.autoRefresh,
                            autoRefreshInterval = config.autoRefreshInterval,
                            eventPhotoPreference = config.eventPhotoPreference,
                            eventGridColumns = config.eventGridColumns,
                        )
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

        /** Called from EventsScreen's DisposableEffect so polling stops when this tab isn't visible. */
        fun setScreenVisible(visible: Boolean) {
            if (screenVisible == visible) return
            screenVisible = visible
            startOrStopAutoRefresh()
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
            // Segment duration for this zoom level. Frigate's own frontend derives motion/gap
            // scale from it: recordings-unavailable scale = segmentDuration, motion scale =
            // segmentDuration / 2 (see api/review/activity/motion contract notes).
            val segmentDurationSeconds = (rangeMs / 1000L / 300L).toInt().coerceIn(1, 3600)
            viewModelScope.launch {
                when (val r = repo.review(camerasParam, after = afterSec, before = beforeSec)) {
                    is ApiResult.Success -> {
                        _state.value = _state.value.copy(reviewSegments = r.data)
                    }

                    else -> {}
                }
            }
            viewModelScope.launch {
                when (
                    val r =
                        repo.recordingGaps(
                            camerasParam,
                            after = afterSec,
                            before = beforeSec,
                            scale = segmentDurationSeconds,
                        )
                ) {
                    is ApiResult.Success -> {
                        _state.value = _state.value.copy(recordingGaps = r.data)
                    }

                    else -> {}
                }
            }
            viewModelScope.launch {
                val motionScaleSeconds = (segmentDurationSeconds / 2).coerceAtLeast(1)
                when (
                    val r =
                        repo.motionActivity(
                            camerasParam,
                            after = afterSec,
                            before = beforeSec,
                            scale = motionScaleSeconds,
                        )
                ) {
                    is ApiResult.Success -> {
                        _state.value = _state.value.copy(motionActivity = r.data)
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

        private var previewClipJob: Job? = null
        private var frameExtractionJob: Job? = null
        private var clearPreviewBitmapJob: Job? = null
        private var previewClipBytes: ByteArray? = null
        private var previewClipCamera: String? = null
        private var previewClipWindowStartMs: Long = 0L
        private var previewClipWindowEndMs: Long = 0L

        /**
         * Resolves which camera was likely active at [timeMs], using whichever activity data
         * (motion, then review) has a point closest to it — the strip mixes multiple cameras, so
         * unlike [net.triton.frigateviewer.feature.events.EventDetail] there's no single camera
         * to fetch a preview clip for without this lookup.
         */
        private fun resolveCameraNear(timeMs: Long): String? {
            val s = _state.value
            val nearestMotion =
                s.motionActivity.minByOrNull { kotlin.math.abs((it.startTime * 1000).toLong() - timeMs) }
            if (nearestMotion != null &&
                kotlin.math.abs((nearestMotion.startTime * 1000).toLong() - timeMs) <= 5 * 60_000L
            ) {
                nearestMotion.camera?.substringBefore(",")?.let { return it }
            }
            val nearestReview =
                s.reviewSegments.minByOrNull { kotlin.math.abs((it.startTime * 1000).toLong() - timeMs) }
            if (nearestReview != null &&
                kotlin.math.abs((nearestReview.startTime * 1000).toLong() - timeMs) <= 5 * 60_000L
            ) {
                return nearestReview.camera
            }
            return null
        }

        /**
         * Called continuously while a finger is on the timeline strip; see
         * [EventDetailViewModel.updateScrubPreview] for the single-camera equivalent. Fetches a
         * ±[PREVIEW_FRAME_WINDOW_HALF_MS] preview clip once per camera+window, then extracts a
         * fresh still frame locally (no network) for every touch-move within that same window.
         */
        fun updateScrubPreview(timeMs: Long?) {
            _state.value = _state.value.copy(scrubPreviewTimeMs = timeMs)
            if (timeMs == null) return
            clearPreviewBitmapJob?.cancel()
            val camera = resolveCameraNear(timeMs) ?: return
            val cachedBytes = previewClipBytes
            if (cachedBytes != null &&
                camera == previewClipCamera &&
                timeMs in previewClipWindowStartMs..previewClipWindowEndMs
            ) {
                extractAndPublishFrame(cachedBytes, timeMs)
                return
            }
            previewClipJob?.cancel()
            previewClipJob =
                viewModelScope.launch {
                    delay(150)
                    val windowStart = timeMs - PREVIEW_FRAME_WINDOW_HALF_MS
                    val windowEnd = timeMs + PREVIEW_FRAME_WINDOW_HALF_MS
                    when (val r = repo.previewClip(camera, windowStart / 1000.0, windowEnd / 1000.0)) {
                        is ApiResult.Success -> {
                            previewClipBytes = r.data
                            previewClipCamera = camera
                            previewClipWindowStartMs = windowStart
                            previewClipWindowEndMs = windowEnd
                            extractAndPublishFrame(r.data, timeMs)
                        }

                        else -> {
                            previewClipBytes = null
                            _state.value = _state.value.copy(scrubPreviewBitmap = null)
                        }
                    }
                }
        }

        private fun extractAndPublishFrame(
            clipBytes: ByteArray,
            timeMs: Long,
        ) {
            frameExtractionJob?.cancel()
            frameExtractionJob =
                viewModelScope.launch(Dispatchers.Default) {
                    val offsetUs = (timeMs - previewClipWindowStartMs) * 1000
                    val bitmap = extractFrame(clipBytes, offsetUs)
                    _state.value = _state.value.copy(scrubPreviewBitmap = bitmap)
                    // Grace period: touch usually releases well before this fetch+extract
                    // pipeline finishes, so keep the bubble up for a bit instead of it never
                    // getting a chance to show. A fresh touch cancels this (see updateScrubPreview).
                    clearPreviewBitmapJob?.cancel()
                    clearPreviewBitmapJob =
                        viewModelScope.launch {
                            delay(4000)
                            _state.value = _state.value.copy(scrubPreviewBitmap = null)
                        }
                }
        }

        private fun applyFilters() {
            _state.value = _state.value.copy(events = filterEvents(allEvents))
        }

        fun refresh(isSilent: Boolean = false) {
            viewModelScope.launch {
                val server = serverRepo.activeServer()
                val ssid = wifiMonitor.ssid.value
                val baseUrl = server?.effectiveBaseUrl(ssid)

                if (server != null && !isSilent) {
                    // Mapping cache rows and building the filter lists must happen on Default
                    // to avoid hitching the Main thread when the database grows.
                    val cached =
                        withContext(Dispatchers.Default) {
                            dao.list(server.id).map { it.toDomain() }
                        }
                    if (cached.isNotEmpty()) {
                        allEvents = cached
                        val filters = getFilters(cached)
                        _state.value =
                            _state.value.copy(
                                events = filterEvents(cached),
                                baseUrl = baseUrl,
                                offlineCache = true,
                                availableCameras = filters.cameras,
                                availableLabels = filters.labels,
                                availableZones = filters.zones,
                            )
                    }
                }
                if (!isSilent) _state.value = _state.value.copy(loading = true, error = null, baseUrl = baseUrl)

                val windowSecs = (System.currentTimeMillis() / 1000.0) - fetchedWindowDays * 24 * 3600
                when (val r = repo.events(limit = 3000, after = windowSecs)) {
                    is ApiResult.Success -> {
                        // For 3000+ items, parsing (via repo.events) and processing are heavy.
                        // Offload all filtering, mapping, and DB preparation to background.
                        withContext(Dispatchers.Default) {
                            val data = r.data
                            allEvents = data
                            val filters = getFilters(data)
                            val filtered = filterEvents(data)
                            val dbRows = if (server != null) data.map { it.toCached(server.id) } else null

                            _state.value =
                                _state.value.copy(
                                    events = filtered,
                                    baseUrl = baseUrl,
                                    loading = false,
                                    offlineCache = false,
                                    availableCameras = filters.cameras,
                                    availableLabels = filters.labels,
                                    availableZones = filters.zones,
                                )

                            if (dbRows != null) dao.upsertAll(dbRows)
                        }
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

        private data class AvailableFilters(
            val cameras: List<String>,
            val labels: List<String>,
            val zones: List<String>,
        )

        private fun getFilters(events: List<FrigateEvent>): AvailableFilters {
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
            return AvailableFilters(cameras, labels, zones)
        }

        private fun updateAvailableFilters(events: List<FrigateEvent>) {
            val filters = getFilters(events)
            _state.value =
                _state.value.copy(
                    availableCameras = filters.cameras,
                    availableLabels = filters.labels,
                    availableZones = filters.zones,
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
