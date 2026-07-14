package net.triton.frigateviewer.feature.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Frigate's "Review" surface: severity-classified segments (alerts vs detections) rather than raw
 * per-object events. One card per [ReviewSegment] — a segment rolls up many detections, so this is
 * a far quieter feed than the Explore list it sits beside.
 */
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

    Column(Modifier.fillMaxSize()) {
        SeverityFilterRow(
            severity = state.severity,
            alertCount = state.alertCount,
            detectionCount = state.detectionCount,
            onSelect = vm::setSeverity,
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
                            "Nothing to review",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(1),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
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

            SeverityRail(
                segments = state.allSegments,
                modifier =
                    Modifier
                        .width(28.dp)
                        .fillMaxSize(),
            )
        }
    }
}

/** The red `37` / orange `92` chips from Frigate's review header. Counts come from the loaded window. */
@Composable
private fun SeverityFilterRow(
    severity: Severity,
    alertCount: Int,
    detectionCount: Int,
    onSelect: (Severity) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SeverityChip(
            label = "Alerts",
            count = alertCount,
            color = severityColor(Severity.ALERT),
            selected = severity == Severity.ALERT,
            onClick = { onSelect(Severity.ALERT) },
        )
        SeverityChip(
            label = "Detections",
            count = detectionCount,
            color = severityColor(Severity.DETECTION),
            selected = severity == Severity.DETECTION,
            onClick = { onSelect(Severity.DETECTION) },
        )
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
 * One review segment. The thumbnail reuses the tracked-object endpoint of the segment's first
 * detection rather than `thumb_path`: `thumb_path` is a server *filesystem* path
 * (`/media/frigate/clips/review/…`) with no documented HTTP route, whereas
 * `api/events/{id}/thumbnail.jpg` is documented and already flows through our authenticated
 * Coil pipeline.
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
                relativePath = "api/events/$thumbId/thumbnail.jpg",
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
 * Vertical activity rail: one tick per segment, positioned by time within the loaded window and
 * colored by severity. Deliberately a plain strip, not a scrubber — dragging it to seek belongs to
 * the motion-review mode, which isn't built yet.
 */
@Composable
private fun SeverityRail(
    segments: List<ReviewSegment>,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
        return
    }

    val newest = segments.maxOf { it.startTime }
    val oldest = segments.minOf { it.startTime }
    val span = (newest - oldest).takeIf { it > 0.0 } ?: 1.0

    val alertColor = severityColor(Severity.ALERT)
    val detectionColor = severityColor(Severity.DETECTION)
    val motionColor = severityColor(Severity.SIGNIFICANT_MOTION)

    Canvas(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        segments.forEach { segment ->
            // Newest at the top, matching the feed's ordering.
            val fraction = ((newest - segment.startTime) / span).toFloat()
            val y = fraction * size.height
            val color =
                when (segment.severityType) {
                    Severity.ALERT -> alertColor
                    Severity.DETECTION -> detectionColor
                    Severity.SIGNIFICANT_MOTION -> motionColor
                }
            drawRect(
                color = color,
                topLeft = Offset(0f, y),
                size = Size(size.width, 3f),
            )
        }
    }
}

/** Alerts read as "act on this", detections as "FYI" — mapped to theme roles, not literal colors. */
@Composable
private fun severityColor(severity: Severity): Color =
    when (severity) {
        Severity.ALERT -> MaterialTheme.colorScheme.error
        Severity.DETECTION -> MaterialTheme.colorScheme.tertiary
        Severity.SIGNIFICANT_MOTION -> MaterialTheme.colorScheme.outline
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
