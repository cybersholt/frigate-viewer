package net.triton.frigateviewer.feature.events

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.model.MotionActivity
import net.triton.frigateviewer.core.model.RecordingGap
import net.triton.frigateviewer.core.model.ReviewSegment

private val TimelinePanelBackground = Color(0xFF121212)

/**
 * Narrow vertical timeline strip for the Events screen right edge — the same severity-colored
 * motion-activity waveform as [HorizontalTimeline] (Event Detail), just compressed into a
 * sidebar-width strip. Mirrors Frigate's own `/review` page timeline column.
 *
 * Bidirectionally synced with [gridState]: scrolling the events grid moves the scrubber, and
 * tapping/dragging the strip scrolls the grid to the nearest event.
 */
@Composable
fun TimelinePanel(
    events: List<FrigateEvent>,
    reviewSegments: List<ReviewSegment>,
    recordingGaps: List<RecordingGap>,
    motionActivity: List<MotionActivity> = emptyList(),
    scrubberTimeMs: Long,
    timeRangeHours: Float,
    gridState: LazyGridState,
    onScrub: (Long) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
    previewFrameFileName: String? = null,
    baseUrl: String? = null,
    imageLoader: ImageLoader? = null,
    onTouchPreview: (Long?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val nowMs = remember { System.currentTimeMillis() }
    val rangeMs = (timeRangeHours * 3_600_000L).toLong()
    val startMs = nowMs - rangeMs
    var previewFractionY by remember { mutableStateOf<Float?>(null) }

    // Grid scroll → scrubber: when the user scrolls the list, move the scrubber to match
    LaunchedEffect(gridState.firstVisibleItemIndex) {
        if (events.isNotEmpty()) {
            val idx = gridState.firstVisibleItemIndex.coerceIn(0, events.size - 1)
            val timeMs = (events[idx].startTime * 1000).toLong()
            onScrub(timeMs)
        }
    }

    val labelPaint =
        remember {
            android.graphics.Paint().apply {
                textSize = 15f
                color = android.graphics.Color.argb(150, 255, 255, 255)
                isAntiAlias = true
            }
        }

    BoxWithConstraints(modifier.background(TimelinePanelBackground)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(startMs, nowMs, rangeMs) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull() ?: continue
                            if (change.pressed) {
                                change.consume()
                                val frac = (change.position.y / size.height).coerceIn(0f, 1f)
                                val timeMs = nowMs - (frac * rangeMs).toLong()
                                onScrub(timeMs)
                                previewFractionY = frac
                                onTouchPreview(timeMs)
                                val nearestIdx =
                                    events.indexOfFirst {
                                        (it.startTime * 1000.0).toLong() <= timeMs
                                    }
                                if (nearestIdx >= 0) {
                                    scope.launch { gridState.scrollToItem(nearestIdx) }
                                }
                            } else if (previewFractionY != null) {
                                previewFractionY = null
                                onTouchPreview(null)
                            }
                        }
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
                centerX = size.width * 0.65f,
                maxBarHalfWidth = size.width * 0.30f,
                numBuckets = 120,
                labelPaint = labelPaint,
                drawLabels = true,
                labelX = 2f,
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp),
        ) {
            IconButton(
                onClick = onZoomIn,
                modifier = Modifier.size(28.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in", tint = Color.White, modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = onZoomOut,
                modifier = Modifier.size(28.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }

        // ── Preview-frame thumbnail bubble, floats left of this narrow strip while touching ──
        previewFractionY?.let { frac ->
            if (imageLoader != null) {
                val bubbleWidth = 120.dp
                val bubbleHeight = bubbleWidth * 9f / 16f
                val offsetY = (maxHeight * frac - bubbleHeight / 2).coerceIn(0.dp, maxHeight - bubbleHeight)
                PreviewThumbnailBubble(
                    fileName = previewFrameFileName,
                    baseUrl = baseUrl,
                    imageLoader = imageLoader,
                    offsetY = offsetY,
                    bubbleWidth = bubbleWidth,
                    modifier = Modifier.align(Alignment.TopStart).offset(x = -(bubbleWidth + 8.dp)),
                )
            }
        }
    }
}
