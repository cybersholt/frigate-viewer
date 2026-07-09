package net.triton.frigateviewer.feature.events

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.ImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.triton.frigateviewer.LocalFullScreenMode
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.image.FrigateImage
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.network.ApiResult
import net.triton.frigateviewer.core.network.FrigateClient
import java.util.Locale
import javax.inject.Inject

enum class EventDetailView { TIMELINE, EVENTS, DETAIL }

private object EventDetailViewPrefs {
    var selected: EventDetailView = EventDetailView.TIMELINE
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface EventDetailEntryPoint {
    fun imageLoader(): ImageLoader

    fun frigateClient(): FrigateClient

    fun serverRepository(): ServerRepository
}

data class EventDetailUiState(
    val loading: Boolean = true,
    val event: FrigateEvent? = null,
    val baseUrl: String? = null,
    val error: String? = null,
    val cameraEvents: List<FrigateEvent> = emptyList(),
    val loadingCameraEvents: Boolean = false,
    val scrubberTimeMs: Long = System.currentTimeMillis(),
    val timeRangeHours: Float = 8f,
    /** Top-of-visible-window time for [HorizontalTimeline]; <= real now, keeps the scrubber
     *  in view across zoom/pan instead of always snapping back to "now". */
    val viewEndMs: Long = System.currentTimeMillis(),
    val selectedView: EventDetailView = EventDetailViewPrefs.selected,
    /** Severity-classified activity for the visible timeline window (Frigate's `api/review`). */
    val reviewSegments: List<net.triton.frigateviewer.core.model.ReviewSegment> = emptyList(),
    /** Gaps in the recording track for the visible timeline window. */
    val recordingGaps: List<net.triton.frigateviewer.core.model.RecordingGap> = emptyList(),
    /** [start, end) of the currently loaded hour-chunk VOD manifest (epoch seconds, UTC-aligned). */
    val recordingChunkAfter: Long = 0L,
    val recordingChunkBefore: Long = 0L,
    /** Physical recording segments for the current chunk — drives seek math (VodSeekUtil). */
    val recordings: List<net.triton.frigateviewer.core.model.RecordingSegment> = emptyList(),
    val recordingsLoading: Boolean = false,
)

@HiltViewModel
class EventDetailViewModel
    @Inject
    constructor(
        private val repo: FrigateRepository,
        private val serverRepo: ServerRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(EventDetailUiState())
        val state = _state.asStateFlow()

        fun load(id: String) {
            viewModelScope.launch {
                val server = serverRepo.activeServer()
                val baseUrl = server?.baseUrl()
                when (val r = repo.event(id)) {
                    is ApiResult.Success -> {
                        val ev = r.data
                        val evStartMs = (ev.startTime * 1000).toLong()
                        val nowMs = System.currentTimeMillis()
                        val ageHours = (nowMs - evStartMs) / 3_600_000f
                        // Ensure the event is always visible: range = age + 2h buffer, min 4h
                        val initialRange = (ageHours + 2f).coerceIn(4f, 168f)
                        val initialRangeMs = (initialRange * 3_600_000L).toLong()
                        // Center the event in view (with headroom above it) instead of always
                        // pinning the visible window to real "now".
                        val initialViewEnd = (evStartMs + initialRangeMs / 2).coerceAtMost(nowMs)
                        _state.value =
                            EventDetailUiState(
                                loading = false,
                                event = ev,
                                baseUrl = baseUrl,
                                scrubberTimeMs = evStartMs,
                                timeRangeHours = initialRange,
                                viewEndMs = initialViewEnd,
                                selectedView = EventDetailViewPrefs.selected,
                            )
                        loadCameraEvents()
                        loadRecordingsForTime(evStartMs)
                        loadTimelineData()
                    }

                    is ApiResult.HttpError -> {
                        _state.value = EventDetailUiState(false, null, baseUrl, "HTTP ${r.code}")
                    }

                    is ApiResult.NetworkError -> {
                        _state.value = EventDetailUiState(false, null, baseUrl, r.cause.message)
                    }

                    is ApiResult.ParseError -> {
                        _state.value = EventDetailUiState(false, null, baseUrl, "Bad response")
                    }
                }
            }
        }

        /**
         * Loads the hour-aligned recording chunk containing [timeMs] and its physical segments
         * (mirrors DynamicVideoPlayer's per-hour `recordings` fetch). No-ops if [timeMs] already
         * falls within the currently loaded chunk.
         */
        fun loadRecordingsForTime(timeMs: Long) {
            val camera = _state.value.event?.camera ?: return
            val chunk = VodSeekUtil.hourChunkFor(timeMs / 1000)
            val s = _state.value
            if (chunk.first == s.recordingChunkAfter && s.recordingChunkBefore != 0L) return
            _state.value =
                s.copy(
                    recordingsLoading = true,
                    recordingChunkAfter = chunk.first,
                    recordingChunkBefore = chunk.last,
                    recordings = emptyList(),
                )
            viewModelScope.launch {
                when (
                    val r =
                        repo.recordings(camera, after = chunk.first.toDouble(), before = chunk.last.toDouble())
                ) {
                    is ApiResult.Success -> {
                        _state.value = _state.value.copy(recordings = r.data, recordingsLoading = false)
                    }

                    else -> {
                        _state.value = _state.value.copy(recordings = emptyList(), recordingsLoading = false)
                    }
                }
            }
        }

        /**
         * Fetches severity-classified review segments + recording gaps for the currently visible
         * timeline window (mirrors Frigate's MotionReviewTimeline data sources). This is why
         * zooming/panning the timeline now reveals detail instead of showing a stale, sparse view.
         */
        fun loadTimelineData() {
            val camera = _state.value.event?.camera ?: return
            val s = _state.value
            val rangeMs = (s.timeRangeHours * 3_600_000L).toLong()
            val afterSec = (s.viewEndMs - rangeMs) / 1000.0
            val beforeSec = s.viewEndMs / 1000.0
            viewModelScope.launch {
                when (val r = repo.review(camera, after = afterSec, before = beforeSec)) {
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
                        repo.recordingGaps(camera, after = afterSec, before = beforeSec, scale = scaleSeconds)
                ) {
                    is ApiResult.Success -> {
                        _state.value = _state.value.copy(recordingGaps = r.data)
                    }

                    else -> {}
                }
            }
        }

        private var timelineDataJob: Job? = null

        /** Debounced [loadTimelineData] for continuous zoom/pan gestures. */
        private fun scheduleTimelineDataRefresh() {
            timelineDataJob?.cancel()
            timelineDataJob =
                viewModelScope.launch {
                    delay(400)
                    loadTimelineData()
                }
        }

        /** Called on player STATE_ENDED — advances to the next hour so playback doesn't stop at :59. */
        fun advanceToNextChunk() {
            val nextChunkStartSec = _state.value.recordingChunkBefore
            if (nextChunkStartSec >= System.currentTimeMillis() / 1000L) return
            loadRecordingsForTime(nextChunkStartSec * 1000L + 1000L)
        }

        private var fetchedWindowDays = 7

        fun loadCameraEvents(force: Boolean = false) {
            val camera = _state.value.event?.camera ?: return
            if (!force && (_state.value.loadingCameraEvents || _state.value.cameraEvents.isNotEmpty())) return
            _state.value = _state.value.copy(loadingCameraEvents = true)
            viewModelScope.launch {
                val windowSecs = (System.currentTimeMillis() / 1000.0) - fetchedWindowDays * 24 * 3600
                when (val r = repo.events(camera = camera, limit = 3000, after = windowSecs)) {
                    is ApiResult.Success -> {
                        _state.value =
                            _state.value.copy(
                                cameraEvents = r.data,
                                loadingCameraEvents = false,
                            )
                    }

                    else -> {
                        _state.value = _state.value.copy(loadingCameraEvents = false)
                    }
                }
            }
        }

        fun setView(view: EventDetailView) {
            EventDetailViewPrefs.selected = view
            _state.value = _state.value.copy(selectedView = view)
        }

        /** Moves the scrubber only — used by in-timeline tap/drag so the view doesn't jump. */
        fun setScrubberTime(timeMs: Long) {
            _state.value = _state.value.copy(scrubberTimeMs = timeMs)
        }

        /** Moves the scrubber AND recenters the view — used by deliberate jumps (date picker). */
        fun jumpToTime(timeMs: Long) {
            val rangeMs = (_state.value.timeRangeHours * 3_600_000L).toLong()
            val nowMs = System.currentTimeMillis()
            val centeredEnd = (timeMs + rangeMs / 2).coerceAtMost(nowMs)
            _state.value = _state.value.copy(scrubberTimeMs = timeMs, viewEndMs = centeredEnd)
            requestMoreHistoryIfNeeded(centeredEnd - rangeMs)
            loadTimelineData()
        }

        /** Pans the visible window by [deltaMs] (positive = toward now, negative = further back). */
        fun panView(deltaMs: Long) {
            val rangeMs = (_state.value.timeRangeHours * 3_600_000L).toLong()
            val nowMs = System.currentTimeMillis()
            val newViewEnd = (_state.value.viewEndMs + deltaMs).coerceAtMost(nowMs)
            _state.value = _state.value.copy(viewEndMs = newViewEnd)
            requestMoreHistoryIfNeeded(newViewEnd - rangeMs)
            scheduleTimelineDataRefresh()
        }

        fun zoomTimeline(factor: Float) {
            val newRange = (_state.value.timeRangeHours * factor).coerceIn(1f, 720f)
            val newRangeMs = (newRange * 3_600_000L).toLong()
            val nowMs = System.currentTimeMillis()
            // Recenter on the scrubber so zooming in keeps whatever you're looking at in view,
            // instead of re-snapping the window to "now" and losing the scrubbed position.
            val centeredEnd = (_state.value.scrubberTimeMs + newRangeMs / 2).coerceAtMost(nowMs)
            _state.value = _state.value.copy(timeRangeHours = newRange, viewEndMs = centeredEnd)
            requestMoreHistoryIfNeeded(centeredEnd - newRangeMs)
            scheduleTimelineDataRefresh()
        }

        private fun requestMoreHistoryIfNeeded(oldestVisibleMs: Long) {
            val neededDays = ((System.currentTimeMillis() - oldestVisibleMs) / 86_400_000L).toInt().coerceAtLeast(7)
            if (neededDays > fetchedWindowDays) {
                fetchedWindowDays = neededDays.coerceAtMost(30)
                loadCameraEvents(force = true)
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    eventId: String,
    onNavigateToEvents: (camera: String?, label: String?, zone: String?) -> Unit = { _, _, _ -> },
    onNavigateToEvent: (String) -> Unit = {},
    onBack: () -> Unit = {},
    vm: EventDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val entryPoint =
        remember {
            EntryPointAccessors.fromApplication(context, EventDetailEntryPoint::class.java)
        }
    val imageLoader = entryPoint.imageLoader()
    val frigateClient = entryPoint.frigateClient()
    val serverRepo = entryPoint.serverRepository()
    val fullScreen = LocalFullScreenMode.current

    val okHttpClient by produceState<okhttp3.OkHttpClient?>(initialValue = null) {
        val server = serverRepo.activeServer()
        value = if (server != null) frigateClient.clientFor(server) else null
    }

    LaunchedEffect(eventId) { vm.load(eventId) }

    var showViewSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    // ── Date picker ──
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = state.scrubberTimeMs)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { vm.jumpToTime(it) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // ── View-switcher bottom sheet (flag button) ──
    if (showViewSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showViewSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Switch View",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                EventDetailView.entries.forEach { view ->
                    val label =
                        when (view) {
                            EventDetailView.TIMELINE -> "Timeline"
                            EventDetailView.EVENTS -> "Events"
                            EventDetailView.DETAIL -> "Detail"
                        }
                    if (view == state.selectedView) {
                        Button(
                            onClick = { showViewSheet = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = {
                                vm.setView(view)
                                showViewSheet = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(label) }
                    }
                }
            }
        }
    }

    when {
        state.loading -> {
            Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }

        state.error != null -> {
            Box(Modifier.fillMaxSize()) {
                Text(
                    "Error: ${state.error}",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }

        state.event != null -> {
            val ev = state.event!!
            val evStartMs = (ev.startTime * 1000).toLong()
            val clipUrl = state.baseUrl?.trimEnd('/')?.let { "$it/api/events/${ev.id}/clip.mp4" }
            // Recordings are only ever stored per top-level camera — "_sub" is a go2rtc live-restream
            // naming convention with no recordings counterpart, so there is no substream VOD to request.
            // Continuous VOD: a range-based master.m3u8 per hour-chunk (matches Frigate's own web
            // frontend, DynamicVideoPlayer.tsx), not the old date/hour/camera/index.m3u8 shape.
            val chunkAfter = state.recordingChunkAfter
            val chunkBefore = state.recordingChunkBefore
            val vodUrl =
                state.baseUrl?.trimEnd('/')?.let { base ->
                    "$base/vod/${ev.camera}/start/$chunkAfter/end/$chunkBefore/master.m3u8"
                }
            val scrubberSec = state.scrubberTimeMs / 1000.0
            val inCurrentChunk = chunkBefore > 0L && scrubberSec >= chunkAfter && scrubberSec <= chunkBefore
            val inpointOffset =
                VodSeekUtil.calculateInpointOffset(chunkAfter.toDouble(), state.recordings.firstOrNull())
            val seekSeconds =
                if (inCurrentChunk) {
                    VodSeekUtil.calculateSeekPosition(scrubberSec, state.recordings, inpointOffset)
                } else {
                    null
                }

            // Scrubbed (or jumped-to) outside the loaded chunk — fetch the hour that contains it.
            LaunchedEffect(state.scrubberTimeMs, chunkAfter, chunkBefore) {
                if (!inCurrentChunk) {
                    vm.loadRecordingsForTime(state.scrubberTimeMs)
                }
            }

            LaunchedEffect(vodUrl, seekSeconds) {
                Log.d("EventDetail", "vod url=$vodUrl seekSeconds=$seekSeconds chunk=[$chunkAfter,$chunkBefore)")
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                // ── Top action bar ──
                if (!fullScreen.value) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }

                        Text(
                            "${ev.camera} · ${ev.label.replaceFirstChar { it.uppercase() }}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )

                        // Date pill — tappable → DatePickerDialog
                        Box(
                            Modifier
                                .background(Color(0xFFE53935), RoundedCornerShape(20.dp))
                                .clickable { showDatePicker = true }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                fmtDatePill(state.scrubberTimeMs),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        Spacer(Modifier.width(4.dp))

                        IconButton(onClick = { showViewSheet = true }) {
                            Icon(Icons.Filled.Flag, contentDescription = "Switch view")
                        }

                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Export clip") },
                                    leadingIcon = { Icon(Icons.Filled.Download, null) },
                                    onClick = {
                                        showMenu = false
                                        if (clipUrl != null) downloadClip(context, ev, clipUrl)
                                    },
                                    enabled = ev.hasClip,
                                )
                                DropdownMenuItem(
                                    text = { Text("Calendar") },
                                    leadingIcon = { Icon(Icons.Filled.CalendarToday, null) },
                                    onClick = {
                                        showMenu = false
                                        showDatePicker = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Filter events") },
                                    leadingIcon = { Icon(Icons.Filled.FilterList, null) },
                                    onClick = {
                                        showMenu = false
                                        onNavigateToEvents(ev.camera, null, null)
                                    },
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                }

                // ── Player / Snapshot ──
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (fullScreen.value) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f),
                        ),
                ) {
                    if (vodUrl != null && okHttpClient != null && state.recordings.isNotEmpty()) {
                        RecordingPlayer(
                            url = vodUrl,
                            okHttpClient = okHttpClient!!,
                            seekSeconds = seekSeconds ?: 0.0,
                            onEnded = vm::advanceToNextChunk,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (state.recordingsLoading) {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                        }
                    } else if (ev.hasSnapshot) {
                        FrigateImage(
                            relativePath = "api/events/${ev.id}/snapshot.jpg?h=720",
                            contentDescription = "${ev.camera} ${ev.label}",
                            baseUrl = state.baseUrl,
                            imageLoader = imageLoader,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Text(
                                "No media available",
                                Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                // ── View content ──
                if (!fullScreen.value) {
                    when (state.selectedView) {
                        EventDetailView.TIMELINE -> {
                            HorizontalTimeline(
                                reviewSegments = state.reviewSegments,
                                recordingGaps = state.recordingGaps,
                                scrubberTimeMs = state.scrubberTimeMs,
                                timeRangeHours = state.timeRangeHours,
                                viewEndMs = state.viewEndMs,
                                onScrub = vm::setScrubberTime,
                                onPan = vm::panView,
                                onZoomChange = vm::zoomTimeline,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }

                        EventDetailView.EVENTS -> {
                            CameraEventsList(
                                events = state.cameraEvents,
                                loading = state.loadingCameraEvents,
                                currentEventId = ev.id,
                                baseUrl = state.baseUrl,
                                imageLoader = imageLoader,
                                onEventClick = onNavigateToEvent,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        EventDetailView.DETAIL -> {
                            EventDetailTimeline(
                                events = state.cameraEvents,
                                currentEventId = ev.id,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Events view ──

@Composable
private fun CameraEventsList(
    events: List<FrigateEvent>,
    loading: Boolean,
    currentEventId: String,
    baseUrl: String?,
    imageLoader: ImageLoader,
    onEventClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        loading -> {
            Box(modifier.fillMaxWidth()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }

        events.isEmpty() -> {
            Box(modifier.fillMaxWidth()) {
                Text("No events found", Modifier.align(Alignment.Center))
            }
        }

        else -> {
            LazyColumn(modifier.fillMaxWidth()) {
                items(events, key = { it.id }) { ev ->
                    EventCard(
                        ev = ev,
                        highlighted = ev.id == currentEventId,
                        baseUrl = baseUrl,
                        imageLoader = imageLoader,
                        onClick = { onEventClick(ev.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EventCard(
    ev: FrigateEvent,
    highlighted: Boolean,
    baseUrl: String?,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else Color.Transparent,
            ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            FrigateImage(
                relativePath = "api/events/${ev.id}/snapshot.jpg?h=360",
                contentDescription = "${ev.camera} ${ev.label}",
                baseUrl = baseUrl,
                imageLoader = imageLoader,
                diskCacheKey = "evt_${ev.id}",
                modifier = Modifier.fillMaxSize(),
            )
            if (highlighted) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(8.dp)
                        .background(Color(0xFFE53935), CircleShape),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(Color(0xFFE53935), CircleShape),
            )
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                fmtShortTime(ev.startTime),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.weight(1f))
            Text(
                fmtRelativeTime(ev.startTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Detail view ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventDetailTimeline(
    events: List<FrigateEvent>,
    currentEventId: String,
    modifier: Modifier = Modifier,
) {
    var alwaysExpand by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var annotationOffset by remember { mutableFloatStateOf(300f) }
    val expandedIds = remember(currentEventId) { mutableStateOf(setOf(currentEventId)) }

    LaunchedEffect(alwaysExpand, currentEventId) {
        if (alwaysExpand) expandedIds.value = expandedIds.value + currentEventId
    }

    // ── Detail View Settings sheet ──
    if (showSettings) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = sheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Detail View Settings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text("Annotation Offset:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("${annotationOffset.toInt()}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(36.dp))
                    Slider(
                        value = annotationOffset,
                        onValueChange = { annotationOffset = it },
                        valueRange = 0f..500f,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { annotationOffset = 0f }, modifier = Modifier.height(32.dp)) {
                        Text("Reset", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    "Milliseconds to offset detect annotations by. Default: 0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Always expand active", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Always expand the active review item's object details when available.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = alwaysExpand, onCheckedChange = { alwaysExpand = it })
                }
            }
        }
    }

    Box(modifier.fillMaxWidth()) {
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                Text("No events", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 64.dp)) {
                items(events, key = { it.id }) { ev ->
                    val expanded = ev.id in expandedIds.value
                    val durationSecs = ev.endTime?.let { (it - ev.startTime).toInt().coerceAtLeast(0) } ?: 0
                    val objectCount = (ev.zones.size + 1).coerceAtLeast(1)
                    val subEvents =
                        buildList {
                            add("${ev.label.replaceFirstChar { it.uppercase() }} detected" to fmtShortTime(ev.startTime))
                            ev.zones.forEach { zone ->
                                val zoneName = zone.replace('_', ' ').replaceFirstChar { it.uppercase() }
                                add("${ev.label.replaceFirstChar { it.uppercase() }} entered $zoneName" to fmtShortTime(ev.startTime + 1.0))
                            }
                            add("${ev.label.replaceFirstChar { it.uppercase() }} left" to fmtShortTime(ev.endTime ?: (ev.startTime + 30.0)))
                        }

                    // ── Event header row ──
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (ev.id == currentEventId) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                } else {
                                    Color.Transparent
                                },
                            ).clickable {
                                expandedIds.value =
                                    if (expanded) expandedIds.value - ev.id else expandedIds.value + ev.id
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(10.dp).background(Color(0xFFE53935), CircleShape))
                        Text(fmtShortTime(ev.startTime), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            "$objectCount object${if (objectCount != 1) "s" else ""} · ${durationSecs}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // ── Expanded sub-events ──
                    if (expanded) {
                        Column(Modifier.padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(
                                    ev.label.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            subEvents.forEach { (label, time) ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .padding(
                                                start = 4.dp,
                                            ).size(6.dp)
                                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape),
                                    )
                                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Text(
                                        time,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }

        // ── Settings pull-out tab ──
        IconButton(
            onClick = { showSettings = true },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Detail View Settings", modifier = Modifier.size(20.dp))
        }
    }
}

// ── Continuous recording (VOD) player ──

private enum class RecordingPlayerState { BUFFERING, READY, ERROR }

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingPlayer(
    url: String,
    okHttpClient: okhttp3.OkHttpClient,
    seekSeconds: Double = 0.0,
    onEnded: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fullScreen = LocalFullScreenMode.current

    var isPlaying by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var clipState by remember(url) { mutableStateOf(RecordingPlayerState.BUFFERING) }
    var errorMsg by remember(url) { mutableStateOf<String?>(null) }
    // Pending seek (ms) applied by the listener once player reaches STATE_READY
    val seekRef =
        remember {
            java.util.concurrent.atomic
                .AtomicLong((seekSeconds * 1000).toLong())
        }

    val player =
        remember(url) {
            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            // setEnableDecoderFallback: some hardware H.264 decoders (e.g. Exynos, on 4K streams)
            // reject setOutputSurface with BAD_INDEX. Without fallback that's a fatal codec crash;
            // with it, ExoPlayer retries the same stream on a software decoder.
            val renderersFactory =
                DefaultRenderersFactory(context)
                    .setEnableDecoderFallback(true)
            ExoPlayer
                .Builder(context, renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(url))
                    volume = 0f
                    prepare()
                    playWhenReady = true
                }
        }

    LaunchedEffect(fullScreen.value) {
        val activity = context as? Activity
        activity?.requestedOrientation =
            if (fullScreen.value) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
    }

    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onVolumeChanged(volume: Float) {
                    isMuted = volume == 0f
                }

                override fun onPlaybackParametersChanged(params: PlaybackParameters) {
                    playbackSpeed = params.speed
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            clipState = RecordingPlayerState.BUFFERING
                        }

                        Player.STATE_READY -> {
                            clipState = RecordingPlayerState.READY
                            // getAndSet consumes the pending seek exactly once. Every segment
                            // boundary rebuffers momentarily and re-enters STATE_READY, so a
                            // plain get() here would re-seek back to the scrub target forever —
                            // playback could never advance past the first segment after a seek.
                            val pending = seekRef.getAndSet(-1L)
                            if (pending > 0L) player.seekTo(pending)
                        }

                        Player.STATE_ENDED -> {
                            // End of this hour-chunk's recordings — advance to the next hour
                            // instead of stopping, so continuous playback doesn't halt at :59.
                            onEnded()
                        }

                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    clipState = RecordingPlayerState.ERROR
                    errorMsg = error.localizedMessage ?: "Playback error"
                    Log.e(
                        "EventDetail",
                        "Playback failed for url=$url errorCode=${error.errorCode} " +
                            "(${error.errorCodeName}): ${error.message}",
                        error.cause,
                    )
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            fullScreen.value = false
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Update seekRef and debounce scrubber drags; if already READY, seek directly
    LaunchedEffect(seekSeconds) {
        val seekMs = (seekSeconds * 1000).toLong()
        seekRef.set(seekMs)
        delay(300)
        if (player.playbackState == Player.STATE_READY) {
            val pending = seekRef.getAndSet(-1L)
            if (pending > 0L) player.seekTo(pending)
        }
    }

    BackHandler(enabled = fullScreen.value) { fullScreen.value = false }

    Box(modifier.background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ── Buffering indicator ──
        if (clipState == RecordingPlayerState.BUFFERING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        }

        // ── Error overlay ──
        if (clipState == RecordingPlayerState.ERROR) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                        .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Playback failed", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                if (errorMsg != null) {
                    Text(
                        errorMsg!!,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                OutlinedButton(
                    onClick = {
                        clipState = RecordingPlayerState.BUFFERING
                        errorMsg = null
                        player.prepare()
                        player.play()
                    },
                ) { Text("Retry", color = Color.White) }
            }
        }

        // ── Playback controls ──
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))),
                ).padding(bottom = 4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { player.volume = if (isMuted) 1f else 0f }) {
                    Icon(
                        if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) {
                    Icon(Icons.Filled.Replay10, "Rewind 10s", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = { if (isPlaying) player.pause() else player.play() }) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(onClick = {
                    val dur = player.duration
                    if (dur > 0) player.seekTo((player.currentPosition + 10_000).coerceAtMost(dur))
                }) {
                    Icon(Icons.Filled.Forward10, "Forward 10s", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Box {
                    IconButton(onClick = { showSpeedMenu = true }) {
                        Text(
                            if (playbackSpeed == playbackSpeed.toInt().toFloat()) "${playbackSpeed.toInt()}x" else "${playbackSpeed}x",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false }) {
                        listOf(1f, 2f, 4f, 8f).forEach { speed ->
                            DropdownMenuItem(
                                text = { Text("${speed.toInt()}x") },
                                onClick = {
                                    player.playbackParameters = PlaybackParameters(speed)
                                    showSpeedMenu = false
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = { fullScreen.value = !fullScreen.value }) {
                    Icon(
                        if (fullScreen.value) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        "Fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

// ── Helpers ──

private fun fmtDatePill(epochMs: Long): String {
    val ldt =
        Instant
            .fromEpochMilliseconds(epochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    val month =
        ldt.month.name
            .take(3)
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    return String.format(Locale.US, "%s %d", month, ldt.dayOfMonth)
}

private fun fmtEventTime(ev: FrigateEvent): String {
    val ldt =
        Instant
            .fromEpochMilliseconds((ev.startTime * 1000).toLong())
            .toLocalDateTime(TimeZone.currentSystemDefault())
    val h12 = ldt.hour % 12
    val dh = if (h12 == 0) 12 else h12
    val ap = if (ldt.hour < 12) "AM" else "PM"
    val time = String.format(Locale.US, "%d:%02d:%02d %s", dh, ldt.minute, ldt.second, ap)
    val month =
        ldt.month.name
            .take(3)
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    return "$month ${ldt.dayOfMonth} · $time"
}

private fun fmtShortTime(epochSecs: Double): String {
    val ldt =
        Instant
            .fromEpochMilliseconds((epochSecs * 1000).toLong())
            .toLocalDateTime(TimeZone.currentSystemDefault())
    val h12 = ldt.hour % 12
    val dh = if (h12 == 0) 12 else h12
    val ap = if (ldt.hour < 12) "AM" else "PM"
    return String.format(Locale.US, "%d:%02d %s", dh, ldt.minute, ap)
}

private fun fmtRelativeTime(epochSecs: Double): String {
    val diffMs = System.currentTimeMillis() - (epochSecs * 1000).toLong()
    val diffSecs = (diffMs / 1000).coerceAtLeast(0)
    return when {
        diffSecs < 60 -> "${diffSecs}s ago"
        diffSecs < 3_600 -> "${diffSecs / 60}m ago"
        diffSecs < 86_400 -> "${diffSecs / 3_600}h ago"
        else -> "${diffSecs / 86_400}d ago"
    }
}

private fun downloadClip(
    context: Context,
    ev: FrigateEvent,
    url: String,
) {
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val request =
        DownloadManager
            .Request(Uri.parse(url))
            .setTitle("${ev.camera} ${ev.label} clip")
            .setDescription("Frigate event ${ev.id}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "frigate_${ev.id}.mp4")
    dm.enqueue(request)
}
