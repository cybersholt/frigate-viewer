package net.triton.frigateviewer.feature.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dagger.hilt.android.EntryPointAccessors
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import net.triton.frigateviewer.core.image.FrigateImage
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.ui.components.CameraPill
import net.triton.frigateviewer.ui.components.SegmentedEventPill
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    onEventClick: (String) -> Unit = {},
    initialCamera: String? = null,
    initialLabel: String? = null,
    initialZone: String? = null,
    vm: EventsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Tells the ViewModel when this tab is actually on screen, so its auto-refresh loop stops
    // while another tab is showing instead of polling forever in the background (the ViewModel
    // itself survives tab switches via Navigation-Compose's saveState/restoreState).
    DisposableEffect(Unit) {
        vm.setScreenVisible(true)
        onDispose { vm.setScreenVisible(false) }
    }

    val context = LocalContext.current
    val imageLoader =
        remember {
            EntryPointAccessors.fromApplication(context, EventDetailEntryPoint::class.java).imageLoader()
        }
    var showFilter by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    var previewYFraction by remember { mutableStateOf<Float?>(null) }

    // Until the user scrolls, the grid stays pinned to the newest event. New events arrive at the top,
    // so a freshly loaded or refreshed list should show them rather than sitting at whatever offset it
    // happened to be at — but once the user has scrolled somewhere deliberately, never yank them back.
    var userHasScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) userHasScrolled = true }
    }
    LaunchedEffect(state.events.firstOrNull()?.id) {
        if (!userHasScrolled && state.events.isNotEmpty()) {
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(initialCamera, initialLabel, initialZone) {
        vm.initFilters(initialCamera, initialLabel, initialZone)
    }

    val activeFilterCount =
        state.selectedCameras.size +
            state.selectedLabels.size +
            state.selectedZones.size +
            (if (state.retainedOnly) 1 else 0)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(end = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedBox(
                badge = {
                    if (activeFilterCount > 0) {
                        Badge { Text("$activeFilterCount") }
                    }
                },
            ) {
                IconButton(onClick = { showFilter = true }, modifier = Modifier.testTag("events_filter_button")) {
                    Icon(Icons.Filled.Tune, contentDescription = "Filters")
                }
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        state.loading && state.events.isEmpty() -> {
                            LoadingIndicator(Modifier.align(Alignment.Center))
                        }

                        state.error != null && state.events.isEmpty() -> {
                            Column(
                                Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("Couldn't load events", style = MaterialTheme.typography.titleLarge)
                                Text(state.error!!, style = MaterialTheme.typography.bodyMedium)
                                Button(
                                    onClick = { vm.refresh() },
                                    modifier = Modifier.padding(top = 16.dp),
                                ) { Text("Retry") }
                            }
                        }

                        state.events.isEmpty() -> {
                            Text("No events", Modifier.align(Alignment.Center))
                        }

                        else -> {
                            PullToRefreshBox(
                                isRefreshing = state.loading,
                                onRefresh = { vm.refresh() },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(state.eventGridColumns),
                                    state = gridState,
                                    contentPadding = PaddingValues(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(state.events, key = { it.id }) { ev ->
                                        EventCard(
                                            ev,
                                            state.baseUrl,
                                            imageLoader,
                                            state.dateFormat,
                                        ) { onEventClick(ev.id) }
                                    }
                                }
                            }
                        }
                    }
                }

                TimelinePanel(
                    itemStartTimesSec = state.events.map { it.startTime },
                    reviewSegments = state.reviewSegments,
                    recordingGaps = state.recordingGaps,
                    motionActivity = state.motionActivity,
                    scrubberTimeMs = state.scrubberTimeMs,
                    timeRangeHours = state.timeRangeHours,
                    gridState = gridState,
                    onScrub = vm::setScrubberTime,
                    onZoomIn = { vm.zoomTimeline(0.5f) },
                    onZoomOut = { vm.zoomTimeline(2f) },
                    modifier = Modifier.width(64.dp).fillMaxHeight(),
                    onTouchPreview = vm::updateScrubPreview,
                    onTouchPositionChanged = { previewYFraction = it },
                )
            }

            // ── Preview-frame thumbnail bubble while touching the timeline strip. Rendered at
            // this outer level (full row width) rather than inside TimelinePanel itself — that
            // strip is only 64dp wide, which coerces a 120dp-wide bubble down to nothing.
            // Horizontally centered across the whole row; vertically follows the touch. ──
            if (state.scrubPreviewBitmap != null) {
                val bubbleHeight = 120.dp * 9f / 16f
                val offsetY =
                    previewYFraction?.let { frac ->
                        (maxHeight * frac - bubbleHeight / 2).coerceIn(0.dp, maxHeight - bubbleHeight)
                    } ?: (maxHeight / 2 - bubbleHeight / 2)
                PreviewThumbnailBubble(
                    bitmap = state.scrubPreviewBitmap,
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = offsetY),
                )
            }
        }
    }

    if (showFilter) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFilter = false },
            sheetState = sheetState,
        ) {
            FilterSheet(
                state = state,
                onToggleCamera = vm::toggleCamera,
                onToggleLabel = vm::toggleLabel,
                onToggleZone = vm::toggleZone,
                onSetRetainedOnly = vm::setRetainedOnly,
                onClearAll = {
                    vm.clearAllFilters()
                    showFilter = false
                },
                onDismiss = { showFilter = false },
            )
        }
    }
}

@Composable
private fun EventCard(
    ev: FrigateEvent,
    baseUrl: String?,
    imageLoader: ImageLoader,
    dateFormat: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("event_card_${ev.id}"),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (ev.hasSnapshot && baseUrl != null) {
                FrigateImage(
                    relativePath = "api/events/${ev.id}/snapshot.jpg",
                    contentDescription = "${ev.camera} ${ev.label}",
                    baseUrl = baseUrl,
                    imageLoader = imageLoader,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }

            // Camera name pill — top-left corner
            CameraPill(
                camera = ev.camera,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
            )

            // Datetime pill — top-right corner
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xBB000000), shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    text = formatEventTime(ev, dateFormat),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }

            // Combined label/score/zone pill — bottom-right corner
            SegmentedEventPill(
                label = ev.label,
                score = (ev.topScore ?: ev.score)?.let { String.format(Locale.US, "%d%%", (it * 100).toInt()) },
                zone = ev.zones.firstOrNull(),
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
            )
        }
    }
}

private fun formatEventTime(
    ev: FrigateEvent,
    dateFormat: String,
): String {
    val ldt =
        Instant
            .fromEpochMilliseconds((ev.startTime * 1000).toLong())
            .toLocalDateTime(TimeZone.currentSystemDefault())
    val hour12 = ldt.hour % 12
    val displayHour = if (hour12 == 0) 12 else hour12
    val amPm = if (ldt.hour < 12) "AM" else "PM"
    val timeStr = String.format(Locale.US, "%d:%02d %s", displayHour, ldt.minute, amPm)
    val durationSecs = ev.endTime?.let { (it - ev.startTime).toInt().coerceAtLeast(0) }
    // Minutes must roll into hours (backlog #11) — a raw M:SS on a 2h50m44s event rendered as
    // the nonsensical "170:44" instead of "2:50:44".
    val durationStr =
        durationSecs?.let {
            val hours = it / 3600
            val minutes = (it % 3600) / 60
            val seconds = it % 60
            if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }

    return if (dateFormat == "numeric") {
        val numeric =
            String.format(
                Locale.US,
                "%02d/%02d/%04d, %s",
                ldt.monthNumber,
                ldt.dayOfMonth,
                ldt.year,
                timeStr,
            )
        if (durationStr != null) "$numeric ($durationStr)" else numeric
    } else {
        val today =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
        val eventDate = ldt.date
        val datePart =
            when (eventDate) {
                today -> {
                    "Today at $timeStr"
                }

                today.minus(1, DateTimeUnit.DAY) -> {
                    "Yesterday at $timeStr"
                }

                else -> {
                    val monthAbbr =
                        ldt.month.name
                            .take(3)
                            .lowercase()
                            .replaceFirstChar { it.uppercase() }
                    "$monthAbbr ${ldt.dayOfMonth} at $timeStr"
                }
            }
        if (durationStr != null) "$datePart · $durationStr" else datePart
    }
}

@Composable
private fun FilterSheet(
    state: EventsUiState,
    onToggleCamera: (String) -> Unit,
    onToggleLabel: (String) -> Unit,
    onToggleZone: (String) -> Unit,
    onSetRetainedOnly: (Boolean) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Filters",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearAll) { Text("Clear all") }
            TextButton(onClick = onDismiss) { Text("Done") }
        }

        if (state.availableCameras.isNotEmpty()) {
            FilterSectionHeader("Cameras")
            state.availableCameras.forEach { cam ->
                FilterCheckboxRow(
                    label = cam,
                    checked = cam in state.selectedCameras,
                    onToggle = { onToggleCamera(cam) },
                )
            }
        }

        if (state.availableLabels.isNotEmpty()) {
            FilterSectionHeader("Labels")
            state.availableLabels.forEach { label ->
                FilterCheckboxRow(
                    label = label,
                    checked = label in state.selectedLabels,
                    onToggle = { onToggleLabel(label) },
                )
            }
        }

        if (state.availableZones.isNotEmpty()) {
            FilterSectionHeader("Zones")
            state.availableZones.forEach { zone ->
                FilterCheckboxRow(
                    label = zone,
                    checked = zone in state.selectedZones,
                    onToggle = { onToggleZone(zone) },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Retained only",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.retainedOnly,
                onCheckedChange = onSetRetainedOnly,
            )
        }
    }
}

@Composable
private fun FilterSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
    )
    HorizontalDivider()
}

@Composable
private fun FilterCheckboxRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
