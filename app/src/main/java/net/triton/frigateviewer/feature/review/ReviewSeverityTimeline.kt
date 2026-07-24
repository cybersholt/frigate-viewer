package net.triton.frigateviewer.feature.review

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.model.MotionActivity
import net.triton.frigateviewer.core.model.ReviewSegment
import net.triton.frigateviewer.feature.events.rememberTimelinePalette
import kotlin.math.abs

/** Ruler label size. `sp`, not `dp`, so it tracks the system font-size setting. */
private val LabelTextSize = 10.sp

/** The scrub handle's time capsule. Same size as the labels but bold, since it is the live value. */
private val HandleTextSize = 10.sp

/**
 * How far from the scrub line a press still counts as grabbing the handle. Gives a 48 dp tall
 * target — Material's minimum — around a handle whose bracket is only 20 dp of that.
 */
private val GrabRadius = 24.dp

/**
 * Review's vertical rail beside the card grid. Rendering lives in [drawReviewRail]; this composable
 * owns the paints, the drag-to-scrub gesture, and the zoom buttons.
 */
@Composable
fun ReviewSeverityTimeline(
    segments: List<ReviewSegment>,
    motionActivity: List<MotionActivity>,
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

    // The gesture block is keyed on nowMs/rangeMs only, so it would otherwise capture the scrub
    // time from the composition that created it and hit-test against a stale handle position.
    val currentScrubMs by rememberUpdatedState(scrubberTimeMs)

    LaunchedEffect(gridState.firstVisibleItemIndex) {
        if (segments.isNotEmpty()) {
            val idx = gridState.firstVisibleItemIndex.coerceIn(0, segments.size - 1)
            onScrub((segments[idx].startTime * 1000).toLong())
        }
    }

    val palette = rememberTimelinePalette()
    val density = LocalDensity.current

    // Deliberately more contrast than the shared `palette.labelArgb` (0.60 alpha), which is tuned
    // for the wide Explore / Event-Detail panels. This rail is ~76 dp across with small type, and
    // at 0.60 the labels wash out against the surface.
    val labelColor =
        MaterialTheme.colorScheme.onSurface
            .copy(alpha = 0.78f)
            .toArgb()
    val labelPaint =
        remember(labelColor, density) {
            android.graphics.Paint().apply {
                textSize = with(density) { LabelTextSize.toPx() }
                color = labelColor
                isAntiAlias = true
            }
        }
    val handlePaint =
        remember(density) {
            android.graphics.Paint().apply {
                textSize = with(density) { HandleTextSize.toPx() }
                color = android.graphics.Color.WHITE
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        }

    BoxWithConstraints(modifier.background(palette.background)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(nowMs, rangeMs) {
                    // Grab-the-handle, not tap-anywhere. A press that doesn't land on the scrub
                    // handle is ignored outright, so brushing the rail while reading the grid no
                    // longer throws playback to a random time. Standing rule for every timeline in
                    // the app — see docs/TODO.md.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val railHeight = size.height.toFloat()
                        if (railHeight <= 0f) return@awaitEachGesture

                        val handleY = ((nowMs - currentScrubMs).toFloat() / rangeMs) * railHeight
                        if (abs(down.position.y - handleY) > GrabRadius.toPx()) {
                            return@awaitEachGesture
                        }
                        down.consume()

                        var lastScrolledIdx = -1
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            val frac = (change.position.y / railHeight).coerceIn(0f, 1f)
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
                        }
                    }
                },
        ) {
            drawReviewRail(
                reviewSegments = segments,
                motionActivity = motionActivity,
                scrubberTimeMs = scrubberTimeMs,
                viewEndMs = nowMs,
                rangeMs = rangeMs,
                timeRangeHours = timeRangeHours,
                labelPaint = labelPaint,
                handlePaint = handlePaint,
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
