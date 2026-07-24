package net.triton.frigateviewer.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dagger.hilt.android.EntryPointAccessors
import net.triton.frigateviewer.core.image.FrigateImage
import net.triton.frigateviewer.core.model.ReviewSegment
import net.triton.frigateviewer.core.model.Severity
import net.triton.frigateviewer.feature.events.EventDetailEntryPoint
import net.triton.frigateviewer.feature.events.TimelineSeverityAlertColor
import net.triton.frigateviewer.feature.events.TimelineSeverityDetectionColor
import net.triton.frigateviewer.feature.events.TimelineSeveritySignificantMotionColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Frigate's "Review" surface: severity-classified segments (alerts vs detections) rather than raw
 * per-object events. One card per [ReviewSegment] — a segment rolls up many detections, so this is
 * a far quieter feed than the Explore list beside it.
 *
 * The timeline strip is [ReviewSeverityTimeline] — a dedicated component, not Explore's
 * [net.triton.frigateviewer.feature.events.TimelinePanel]. Frigate's own frontend draws these two
 * differently (solid severity pills here vs a motion waveform there); see that file's doc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onOpenEvent: (String) -> Unit = {},
    vm: ReviewViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val imageLoader: ImageLoader =
        remember {
            EntryPointAccessors
                .fromApplication(context, EventDetailEntryPoint::class.java)
                .imageLoader()
        }

    val gridState = rememberLazyGridState()
    var showFilters by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            // The outer Scaffold (MainActivity) already insets NavHost content for the status
            // bar. TopAppBar's default `windowInsets` reserves that space a SECOND time — the
            // combination is what pushed this screen's content well below where Cameras/Explore
            // sit, since neither of those uses its own TopAppBar.
            windowInsets = WindowInsets(0),
            title = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeverityChip(
                        label = "Alerts",
                        count = state.alertCount,
                        color = severityColor(Severity.ALERT),
                        selected = Severity.ALERT in state.severities,
                        onClick = { vm.toggleSeverity(Severity.ALERT) },
                    )
                    SeverityChip(
                        label = "Detections",
                        count = state.detectionCount,
                        color = severityColor(Severity.DETECTION),
                        selected = Severity.DETECTION in state.severities,
                        onClick = { vm.toggleSeverity(Severity.DETECTION) },
                    )
                }
            },
            actions = {
                IconButton(onClick = { showFilters = true }) {
                    BadgedBox(
                        badge = {
                            if (state.activeFilterCount > 0) {
                                Badge { Text("${state.activeFilterCount}") }
                            }
                        },
                    ) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Filters")
                    }
                }
            },
        )

        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when {
                    state.loading && state.allSegments.isEmpty() -> {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }

                    state.error != null && state.allSegments.isEmpty() -> {
                        Text(
                            state.error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    state.segments.isEmpty() -> {
                        Text(
                            "Nothing matches these filters",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    else -> {
                        PullToRefreshBox(
                            isRefreshing = state.loading,
                            onRefresh = { vm.refresh() },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(1),
                                state = gridState,
                                contentPadding = PaddingValues(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(state.segments, key = { it.id }) { segment ->
                                    ReviewCard(
                                        segment = segment,
                                        baseUrl = state.baseUrl,
                                        imageLoader = imageLoader,
                                        onClick = {
                                            segment.data.detections
                                                .firstOrNull()
                                                ?.let(onOpenEvent)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ReviewSeverityTimeline(
                segments = state.segments,
                motionActivity = state.motionActivity,
                scrubberTimeMs = state.scrubberTimeMs,
                timeRangeHours = state.timeRangeHours,
                gridState = gridState,
                onScrub = vm::setScrubberTime,
                onZoomIn = { vm.zoomTimeline(0.5f) },
                onZoomOut = { vm.zoomTimeline(2f) },
                modifier =
                    Modifier
                        // 64.dp could not fit a time label, a ruler and the activity strip without
                        // the label colliding with the ticks. The grid beside it is a single
                        // column of wide cards, so it gives up the 12 dp without reflowing.
                        .width(76.dp)
                        .fillMaxHeight(),
            )
        }
    }

    if (showFilters) {
        ModalBottomSheet(
            onDismissRequest = { showFilters = false },
            sheetState = sheetState,
        ) {
            ReviewFilterSheet(state = state, vm = vm, onClose = { showFilters = false })
        }
    }
}

@Composable
private fun ReviewFilterSheet(
    state: ReviewUiState,
    vm: ReviewViewModel,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text("Filter", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))

        SwitchRow("Alerts", Severity.ALERT in state.severities) { vm.toggleSeverity(Severity.ALERT) }
        SwitchRow("Detections", Severity.DETECTION in state.severities) { vm.toggleSeverity(Severity.DETECTION) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SwitchRow("Show reviewed", state.showReviewed) { vm.setShowReviewed(it) }

        FilterSection(
            title = "Cameras",
            allLabel = "All cameras",
            options = state.availableCameras,
            selected = state.selectedCameras,
            onToggle = vm::toggleCamera,
        )
        FilterSection(
            title = "Labels",
            allLabel = "All labels",
            options = state.availableLabels,
            selected = state.selectedLabels,
            onToggle = vm::toggleLabel,
        )
        FilterSection(
            title = "Zones",
            allLabel = "All zones",
            options = state.availableZones,
            selected = state.selectedZones,
            onToggle = vm::toggleZone,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Done") }
            OutlinedButton(onClick = vm::resetFilters, modifier = Modifier.weight(1f)) { Text("Reset") }
        }
    }
}

/**
 * A filter group. An empty selection *is* "all" — so there's no separate all-toggle to keep in
 * sync, and the two can never contradict each other. The group hides itself when the loaded window
 * contains nothing to filter by (e.g. no zones configured).
 */
@Composable
private fun FilterSection(
    title: String,
    allLabel: String,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    if (options.isEmpty()) return

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
    Text(
        if (selected.isEmpty()) allLabel else "${selected.size} selected",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    options.forEach { option ->
        SwitchRow(
            // Frigate's zone/label/camera identifiers are snake_case on the wire ("front_yard"),
            // and the raw underscores were showing through in this sheet. Same treatment
            // EventDetail already gives zone chips — display prettified, filter on the raw value.
            label = option.replace('_', ' ').replaceFirstChar { it.uppercase() },
            checked = option in selected,
        ) { onToggle(option) }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SeverityChip(
    label: String,
    count: Int,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$count", color = color, fontWeight = FontWeight.Bold)
                Text(label)
            }
        },
    )
}

/**
 * One review segment. The thumbnail resolves through the segment's first detection rather than
 * `thumb_path`: `thumb_path` is a server *filesystem* path (`/media/frigate/clips/review/…`) with
 * no documented HTTP route, whereas the `api/events/{id}/…` routes are documented and already flow
 * through our authenticated Coil pipeline.
 *
 * Uses `snapshot.jpg`, not `thumbnail.jpg`. Frigate's thumbnail is a small crop scaled around the
 * detected object — fine for a list row, visibly mushy stretched across a full-width 16:9 card,
 * which is why these cards read as much lower quality than Explore's (Explore has always used
 * `snapshot.jpg`; see EventsScreen.kt).
 */
@Composable
private fun ReviewCard(
    segment: ReviewSegment,
    baseUrl: String?,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    val thumbId = segment.data.detections.firstOrNull()

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = thumbId != null, onClick = onClick),
    ) {
        if (thumbId != null) {
            FrigateImage(
                // No `?h=` — measured 2026-07-23 against the live server: snapshot.jpg returns
                // 640x360 / ~55 KB regardless (h=200 and h=480 both come back 640x360), so the
                // param is dead weight here. Don't re-add it thinking it resizes anything.
                relativePath = "api/events/$thumbId/snapshot.jpg",
                contentDescription = "${segment.camera} ${segment.severity}",
                baseUrl = baseUrl,
                imageLoader = imageLoader,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (segment.data.objects.isNotEmpty()) {
            Text(
                segment.data.objects
                    .joinToString(", ")
                    .replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(
                            severityColor(segment.severityType).copy(alpha = 0.85f),
                            RoundedCornerShape(6.dp),
                        ).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Text(
            relativeTime(segment.startTime),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
        )
        Text(
            absoluteTime(segment.startTime),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
        )
    }
}

/**
 * Deliberately Frigate's own literal severity hues (dark red / orange / yellow — see
 * `TimelineSeverity*Color` in the events package), not theme roles. This is the same exception
 * [net.triton.frigateviewer.feature.events.TimelineRenderer] already documents: severity color
 * encodes meaning shared with Frigate's own web frontend (chips, cards, calendar dots, the
 * timeline rail), so it must read the same everywhere rather than following the theme.
 */
private fun severityColor(severity: Severity): Color =
    when (severity) {
        Severity.ALERT -> TimelineSeverityAlertColor
        Severity.DETECTION -> TimelineSeverityDetectionColor
        Severity.SIGNIFICANT_MOTION -> TimelineSeveritySignificantMotionColor
    }

private fun relativeTime(epochSecs: Double): String {
    val deltaMs = System.currentTimeMillis() - (epochSecs * 1000).toLong()
    val minutes = deltaMs / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}

private fun absoluteTime(epochSecs: Double): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        .format(Date((epochSecs * 1000).toLong()))
