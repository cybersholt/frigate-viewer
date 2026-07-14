package net.triton.frigateviewer.feature.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.model.ReviewSegment
import net.triton.frigateviewer.feature.events.drawSeverityEventTimeline
import net.triton.frigateviewer.feature.events.rememberTimelinePalette

/**
 * Review's vertical severity rail — one rounded pill per visible [ReviewSegment], spanning its
 * actual start→end time, colored by severity (dimmed once reviewed). Deliberately a different
 * visual from Explore's [net.triton.frigateviewer.feature.events.TimelinePanel]: Frigate's own
 * frontend renders these with two separate components (`EventReviewTimeline` here vs
 * `MotionReviewTimeline` there) because a bucketed motion waveform and a per-item severity bar
 * answer different questions. See [drawSeverityEventTimeline] for the render logic.
 */
@Composable
fun ReviewSeverityTimeline(
    segments: List<ReviewSegment>,
    scrubberTimeMs: Long,
    timeRangeHours: Float,
    gridState: LazyGridState,
    onScrub: (Long) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val nowMs = remember { System.currentTimeMillis() }
    val rangeMs = (timeRangeHours * 3_600_000L).toLong()
    val startMs = nowMs - rangeMs

    LaunchedEffect(gridState.firstVisibleItemIndex) {
        if (segments.isNotEmpty()) {
            val idx = gridState.firstVisibleItemIndex.coerceIn(0, segments.size - 1)
            onScrub((segments[idx].startTime * 1000).toLong())
        }
    }

    val palette = rememberTimelinePalette()
    val labelPaint =
        remember(palette) {
            android.graphics.Paint().apply {
                textSize = 15f
                color = palette.labelArgb
                isAntiAlias = true
            }
        }

    BoxWithConstraints(modifier.background(palette.background)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(startMs, nowMs, rangeMs) {
                    awaitPointerEventScope {
                        var lastScrolledIdx = -1
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull() ?: continue
                            if (change.pressed) {
                                change.consume()
                                val frac = (change.position.y / size.height).coerceIn(0f, 1f)
                                val timeMs = nowMs - (frac * rangeMs).toLong()
                                onScrub(timeMs)
                                val nearestIdx =
                                    segments.indexOfFirst {
                                        (it.startTime * 1000.0).toLong() <= timeMs
                                    }
                                if (nearestIdx >= 0 && nearestIdx != lastScrolledIdx) {
                                    lastScrolledIdx = nearestIdx
                                    scope.launch { gridState.scrollToItem(nearestIdx) }
                                }
                            } else {
                                lastScrolledIdx = -1
                            }
                        }
                    }
                },
        ) {
            drawSeverityEventTimeline(
                reviewSegments = segments,
                scrubberTimeMs = scrubberTimeMs,
                viewEndMs = nowMs,
                rangeMs = rangeMs,
                timeRangeHours = timeRangeHours,
                centerX = size.width * 0.65f,
                pillHalfWidth = size.width * 0.12f,
                labelPaint = labelPaint,
                drawLabels = true,
                labelX = 2f,
                palette = palette,
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp),
        ) {
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
