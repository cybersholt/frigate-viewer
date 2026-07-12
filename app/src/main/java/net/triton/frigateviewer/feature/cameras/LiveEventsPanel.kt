package net.triton.frigateviewer.feature.cameras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.triton.frigateviewer.core.image.FrigateImage
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.model.MotionActivity
import net.triton.frigateviewer.core.model.RecordingGap
import net.triton.frigateviewer.core.model.ReviewSegment
import net.triton.frigateviewer.feature.events.drawActivityTimeline
import net.triton.frigateviewer.feature.events.rememberTimelinePalette
import java.util.Locale

private enum class PanelMode { List, Timeline }

/**
 * Recent-events side panel shown next to the live view in fullscreen landscape (#16 List mode,
 * #17 Timeline mode) — mirrors the reference wishlist layout (`screenshots/todo_005/006.png`)
 * using this app's own Material3 components rather than a pixel-for-pixel recreation.
 *
 * List mode fetches eagerly on mount (cheap: one `events` call). Timeline mode's three API calls
 * (review/recordingGaps/motionActivity) are fetched lazily — only the first time the user actually
 * switches to it, and again whenever the zoom level changes — since a landscape-fullscreen viewer
 * who never opens Timeline shouldn't pay for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveEventsPanel(
    cameraName: String,
    baseUrl: String?,
    imageLoader: ImageLoader,
    events: List<FrigateEvent>,
    eventsLoading: Boolean,
    reviewSegments: List<ReviewSegment>,
    recordingGaps: List<RecordingGap>,
    motionActivity: List<MotionActivity>,
    timelineLoading: Boolean,
    onRequestEvents: () -> Unit,
    onRequestTimeline: (timeRangeHours: Float) -> Unit,
    onOpenEvents: () -> Unit,
    /** Tapping an event plays it in place beside this panel — it does not navigate to the Events screen. */
    onPlayEvent: (FrigateEvent) -> Unit = {},
    /** Tapping the timeline plays this camera's recording from that moment (epoch millis). */
    onPlayFromTime: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(cameraName) { onRequestEvents() }

    var mode by remember(cameraName) { mutableStateOf(PanelMode.List) }
    var timeRangeHours by remember(cameraName) { mutableStateOf(6f) }
    var selectedLabel by remember(cameraName) { mutableStateOf<String?>(null) }
    var showFilterMenu by remember { mutableStateOf(false) }

    LaunchedEffect(cameraName, mode, timeRangeHours) {
        if (mode == PanelMode.Timeline) onRequestTimeline(timeRangeHours)
    }

    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Recent Events",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                SegmentedButton(
                    selected = mode == PanelMode.List,
                    onClick = { mode = PanelMode.List },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "List view", modifier = Modifier.size(18.dp))
                }
                SegmentedButton(
                    selected = mode == PanelMode.Timeline,
                    onClick = { mode = PanelMode.Timeline },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Icon(Icons.Filled.Timeline, contentDescription = "Timeline view", modifier = Modifier.size(18.dp))
                }
            }
            if (mode == PanelMode.List) {
                Box {
                    IconButton(onClick = { showFilterMenu = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Filter by label")
                    }
                    DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("All") },
                            onClick = {
                                selectedLabel = null
                                showFilterMenu = false
                            },
                        )
                        events.map { it.label }.distinct().sorted().forEach { label ->
                            DropdownMenuItem(
                                text = { Text(label.replaceFirstChar { c -> c.uppercase() }) },
                                onClick = {
                                    selectedLabel = label
                                    showFilterMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                PanelMode.List -> {
                    val filtered = if (selectedLabel == null) events else events.filter { it.label == selectedLabel }
                    when {
                        eventsLoading && events.isEmpty() -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center).size(28.dp),
                                strokeWidth = 2.dp,
                            )
                        }

                        filtered.isEmpty() -> {
                            Text(
                                "No recent events",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }

                        else -> {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(filtered, key = { it.id }) { ev ->
                                    RecentEventRow(ev, baseUrl, imageLoader, onClick = { onPlayEvent(ev) })
                                }
                            }
                        }
                    }
                }

                PanelMode.Timeline -> {
                    if (timelineLoading && reviewSegments.isEmpty() && recordingGaps.isEmpty()) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center).size(28.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        LiveTimelineStrip(
                            reviewSegments = reviewSegments,
                            recordingGaps = recordingGaps,
                            motionActivity = motionActivity,
                            timeRangeHours = timeRangeHours,
                            onZoomIn = { timeRangeHours = (timeRangeHours / 2f).coerceAtLeast(0.5f) },
                            onZoomOut = { timeRangeHours = (timeRangeHours * 2f).coerceAtMost(72f) },
                            onPlayFromTime = onPlayFromTime,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        TextButton(onClick = onOpenEvents, modifier = Modifier.fillMaxWidth()) {
            Text("View all events")
        }
    }
}

@Composable
private fun RecentEventRow(
    ev: FrigateEvent,
    baseUrl: String?,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    val (timeStr, durationStr) = remember(ev.id) { formatPanelEventTime(ev) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ev.hasSnapshot) {
                FrigateImage(
                    relativePath = "api/events/${ev.id}/snapshot.jpg",
                    contentDescription = null,
                    baseUrl = baseUrl,
                    imageLoader = imageLoader,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "${ev.label.replaceFirstChar { it.uppercase() }} detected",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Text(
                    timeStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (durationStr != null) {
                Text(
                    durationStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Compact vertical timeline strip (#17) — reuses the exact same severity-colored motion-activity
 * waveform as the Events feature's [net.triton.frigateviewer.feature.events.HorizontalTimeline]/
 * `TimelinePanel` (shared via the internal `drawActivityTimeline`), just without their grid-scroll
 * sync (there's no events grid alongside this panel) — tap-to-scrub plus zoom in/out only.
 */
@Composable
private fun LiveTimelineStrip(
    reviewSegments: List<ReviewSegment>,
    recordingGaps: List<RecordingGap>,
    motionActivity: List<MotionActivity>,
    timeRangeHours: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onPlayFromTime: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowMs = remember { System.currentTimeMillis() }
    var scrubberTimeMs by remember { mutableStateOf(nowMs) }
    val rangeMs = (timeRangeHours * 3_600_000L).toLong()

    // Reading the latest callback/geometry inside a pointerInput that must not restart mid-gesture.
    val currentOnPlayFromTime by rememberUpdatedState(onPlayFromTime)

    val palette = rememberTimelinePalette()
    val labelPaint =
        remember(palette) {
            android.graphics.Paint().apply {
                textSize = 15f
                color = palette.labelArgb
                isAntiAlias = true
            }
        }

    Box(modifier.background(palette.background)) {
        Canvas(
            Modifier
                .fillMaxSize()
                // Drag scrubs; a tap commits. Previously the strip only moved a scrubber line and had
                // no way to act on it, which is why Timeline mode felt inert — you could point at a
                // moment but never watch it.
                .pointerInput(nowMs, rangeMs) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (change.pressed) {
                                change.consume()
                                val frac = (change.position.y / size.height).coerceIn(0f, 1f)
                                scrubberTimeMs = nowMs - (frac * rangeMs).toLong()
                            }
                        }
                    }
                }.pointerInput(nowMs, rangeMs) {
                    detectTapGestures { offset ->
                        val frac = (offset.y / size.height).coerceIn(0f, 1f)
                        val tappedMs = nowMs - (frac * rangeMs).toLong()
                        scrubberTimeMs = tappedMs
                        currentOnPlayFromTime(tappedMs)
                    }
                },
        ) {
            drawActivityTimeline(
                reviewSegments = reviewSegments,
                recordingGaps = recordingGaps,
                motionActivity = motionActivity,
                scrubberTimeMs = scrubberTimeMs,
                viewEndMs = nowMs,
                rangeMs = rangeMs,
                timeRangeHours = timeRangeHours,
                centerX = size.width * 0.6f,
                maxBarHalfWidth = size.width * 0.32f,
                numBuckets = 100,
                labelPaint = labelPaint,
                drawLabels = true,
                labelX = 2f,
                palette = palette,
            )
        }

        Column(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
            IconButton(
                onClick = onZoomIn,
                modifier =
                    Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), CircleShape),
            ) {
                Icon(
                    Icons.Filled.ZoomIn,
                    contentDescription = "Zoom in",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(
                onClick = onZoomOut,
                modifier =
                    Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), CircleShape),
            ) {
                Icon(
                    Icons.Filled.ZoomOut,
                    contentDescription = "Zoom out",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Time + duration strings for a compact panel row — deliberately simpler than EventsScreen's
 * `formatEventTime` (no numeric-date-format branch; this panel only ever shows a time-of-day).
 */
private fun formatPanelEventTime(ev: FrigateEvent): Pair<String, String?> {
    val ldt =
        Instant
            .fromEpochMilliseconds((ev.startTime * 1000).toLong())
            .toLocalDateTime(TimeZone.currentSystemDefault())
    val hour12 = ldt.hour % 12
    val displayHour = if (hour12 == 0) 12 else hour12
    val amPm = if (ldt.hour < 12) "AM" else "PM"
    val timeStr = String.format(Locale.US, "%d:%02d %s", displayHour, ldt.minute, amPm)
    val durationSecs = ev.endTime?.let { (it - ev.startTime).toInt().coerceAtLeast(0) }
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
    return timeStr to durationStr
}
