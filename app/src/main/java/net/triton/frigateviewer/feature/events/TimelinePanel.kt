package net.triton.frigateviewer.feature.events

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.model.MotionActivity
import net.triton.frigateviewer.core.model.RecordingGap
import net.triton.frigateviewer.core.model.ReviewSegment
import kotlin.math.abs

/**
 * Narrow vertical timeline strip for the Events screen right edge — the same severity-colored
 * motion-activity waveform as [HorizontalTimeline] (Event Detail), just compressed into a
 * sidebar-width strip. Mirrors Frigate's own `MotionReviewTimeline` (Explore/History), not the
 * `/review` page's timeline — that one draws solid severity pills, not a motion waveform; see
 * [net.triton.frigateviewer.feature.review.ReviewSeverityTimeline].
 *
 * Bidirectionally synced with [gridState]: scrolling the events grid moves the scrubber, and
 * tapping/dragging the strip scrolls the grid to the nearest event. The preview-frame thumbnail
 * bubble is rendered by the caller (this strip is too narrow — 64dp — to host a 120dp-wide
 * bubble itself without its size getting coerced down by the parent's width constraint).
 */
@Composable
fun TimelinePanel(
    /**
     * Start times (epoch seconds) of whatever the grid is showing, newest first — events on
     * Explore, review segments on Review. Only used to sync the scrubber with the grid, so the
     * strip doesn't care what the items actually are.
     */
    itemStartTimesSec: List<Double>,
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
    onTouchPreview: (Long?) -> Unit = {},
    onTouchPositionChanged: (Float?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val nowMs = remember { System.currentTimeMillis() }
    val rangeMs = (timeRangeHours * 3_600_000L).toLong()
    val startMs = nowMs - rangeMs

    // Grid scroll → scrubber: when the user scrolls the list, move the scrubber to match
    LaunchedEffect(gridState.firstVisibleItemIndex) {
        if (itemStartTimesSec.isNotEmpty()) {
            val idx = gridState.firstVisibleItemIndex.coerceIn(0, itemStartTimesSec.size - 1)
            val timeMs = (itemStartTimesSec[idx] * 1000).toLong()
            onScrub(timeMs)
        }
    }

    // Keyed gesture block would otherwise hit-test against a stale handle position.
    val currentScrubMs by rememberUpdatedState(scrubberTimeMs)

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
                .pointerInput(nowMs, rangeMs) {
                    val grabRadiusPx = 24.dp.toPx()
                    // Grab-the-handle, same rule as every other timeline in the app: a press that
                    // isn't on the red scrubber is ignored, so brushing the strip no longer yanks
                    // the grid to a different time.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val height = size.height.toFloat()
                        if (height <= 0f) return@awaitEachGesture

                        val scrubY = ((nowMs - currentScrubMs).toFloat() / rangeMs) * height
                        if (abs(down.position.y - scrubY) > grabRadiusPx) return@awaitEachGesture
                        down.consume()

                        var lastScrolledIdx = -1
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            val frac = (change.position.y / height).coerceIn(0f, 1f)
                            val timeMs = nowMs - (frac * rangeMs).toLong()
                            onScrub(timeMs)
                            onTouchPreview(timeMs)
                            onTouchPositionChanged(frac)
                            val nearestIdx =
                                itemStartTimesSec.indexOfFirst {
                                    (it * 1000.0).toLong() <= timeMs
                                }
                            if (nearestIdx >= 0 && nearestIdx != lastScrolledIdx) {
                                lastScrolledIdx = nearestIdx
                                scope.launch { gridState.scrollToItem(nearestIdx) }
                            }
                        }
                        onTouchPreview(null)
                        onTouchPositionChanged(null)
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
                palette = palette,
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
