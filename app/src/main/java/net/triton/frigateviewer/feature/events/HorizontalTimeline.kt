package net.triton.frigateviewer.feature.events

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.triton.frigateviewer.core.model.MotionActivity
import net.triton.frigateviewer.core.model.RecordingGap
import net.triton.frigateviewer.core.model.ReviewSegment
import java.util.Locale
import kotlin.math.abs

/**
 * Full-screen vertical timeline for Event Detail — Timeline view. Mirrors Frigate's own web
 * frontend (MotionReviewTimeline/EventReviewTimeline): a recording-availability track (gaps
 * darkened) with severity-colored review segments (alert/detection/significant_motion) drawn
 * over it, instead of flat per-event pills.
 *
 * [viewEndMs] (<= real "now") anchors y=0 (top); it moves as the caller zooms/pans, so the
 * scrubbed position stays in view instead of the window always snapping back to "now". Past
 * scrolls downward. The red scrubber is a movable line that starts at the event's detection
 * time. Bar WIDTH represents activity density: more/higher-severity segments in a bucket →
 * wider, more saturated bar.
 *
 * Gestures: a tap (no meaningful vertical movement) jumps the scrubber to that time; a
 * vertical drag pans the view instead, revealing time outside the current window.
 */
@Composable
fun HorizontalTimeline(
    reviewSegments: List<ReviewSegment>,
    recordingGaps: List<RecordingGap>,
    motionActivity: List<MotionActivity> = emptyList(),
    scrubberTimeMs: Long,
    timeRangeHours: Float,
    viewEndMs: Long,
    onScrub: (Long) -> Unit,
    onPan: (Long) -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onTouchPreview: (Long?) -> Unit = {},
    onTouchPositionChanged: (Float?) -> Unit = {},
) {
    val rangeMs = (timeRangeHours * 3_600_000L).toLong()
    val oldestMs = viewEndMs - rangeMs
    // rememberUpdatedState so pointerInput(Unit) always reads latest values without restarting
    val currentViewEndMs by rememberUpdatedState(viewEndMs)
    val currentRangeMs by rememberUpdatedState(rangeMs)
    val currentOldestMs by rememberUpdatedState(oldestMs)
    val currentScrubMs by rememberUpdatedState(scrubberTimeMs)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnPan by rememberUpdatedState(onPan)
    val currentOnTouchPreview by rememberUpdatedState(onTouchPreview)
    val currentOnTouchPositionChanged by rememberUpdatedState(onTouchPositionChanged)

    val palette = rememberTimelinePalette()
    val labelPaint =
        remember(palette) {
            android.graphics.Paint().apply {
                textSize = 26f
                color = palette.labelArgb
                isAntiAlias = true
            }
        }

    BoxWithConstraints(modifier.background(palette.background)) {
        fun timeAtFraction(frac: Float): Long =
            (currentViewEndMs - frac * currentRangeMs).toLong().coerceIn(currentOldestMs, currentViewEndMs)

        Canvas(
            Modifier
                .fillMaxSize()
                // Unit key: gesture handler never restarts mid-drag when zoom/pan changes
                .pointerInput(Unit) {
                    // Pan only. Scrubbing belongs to the handle overlay below, which owns its own
                    // pointer events — the way Frigate's `use-draggable-element` does it. Sharing
                    // one gesture between "pan" and "hit-test the scrubber line" is what made fine
                    // adjustment impossible: a finger a few px off the line silently panned
                    // instead, and in landscape the rail is barely half as tall so missing was easy.
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val height = size.height.toFloat()
                        if (height <= 0f) return@awaitEachGesture

                        var isPanning = false
                        var lastY = down.position.y
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) break

                            val dy = change.position.y - lastY
                            if (!isPanning && abs(change.position.y - down.position.y) > touchSlop) {
                                isPanning = true
                            }
                            if (isPanning) {
                                change.consume()
                                val msPerPx = currentRangeMs.toFloat() / height
                                // Content follows the finger: drag down reveals newer time
                                // (toward now), drag up reveals further into the past.
                                currentOnPan((dy * msPerPx).toLong())
                            }
                            lastY = change.position.y
                        }
                        currentOnTouchPreview(null)
                        currentOnTouchPositionChanged(null)
                    }
                },
        ) {
            drawActivityTimeline(
                reviewSegments = reviewSegments,
                recordingGaps = recordingGaps,
                motionActivity = motionActivity,
                scrubberTimeMs = scrubberTimeMs,
                viewEndMs = viewEndMs,
                rangeMs = rangeMs,
                timeRangeHours = timeRangeHours,
                centerX = size.width / 2f,
                maxBarHalfWidth = size.width * 0.38f,
                numBuckets = 160,
                labelPaint = labelPaint,
                drawLabels = true,
                labelX = 10f,
                palette = palette,
            )
        }

        // ── Draggable scrub handle ──
        // A real, sized element that owns its pointer events, not a hit-test inside the canvas
        // gesture. This mirrors Frigate's `use-draggable-element`: the handle receives the drag,
        // so dragging it can never be mistaken for panning, and it needs no grab threshold
        // because it has genuine size. The faint red band is that touch target made visible —
        // the reference PWA draws the same band around its scrubber.
        val handleHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val handleFraction = ((viewEndMs - scrubberTimeMs).toFloat() / rangeMs).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(ScrubHandleTouchHeight)
                .offset(y = maxHeight * handleFraction - ScrubHandleTouchHeight / 2)
                .background(TimelineScrubberColor.copy(alpha = 0.12f))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            currentOnTouchPreview(currentScrubMs)
                            currentOnTouchPositionChanged(
                                ((currentViewEndMs - currentScrubMs).toFloat() / currentRangeMs)
                                    .coerceIn(0f, 1f),
                            )
                        },
                        onDragEnd = {
                            currentOnTouchPreview(null)
                            currentOnTouchPositionChanged(null)
                        },
                        onDragCancel = {
                            currentOnTouchPreview(null)
                            currentOnTouchPositionChanged(null)
                        },
                    ) { change, drag ->
                        change.consume()
                        if (handleHeightPx <= 0f) return@detectDragGestures
                        val msPerPx = currentRangeMs.toFloat() / handleHeightPx
                        // Dragging down moves toward older footage, matching the rail's top=now
                        // orientation. Applying the delta to the *current* scrub time (rather than
                        // mapping absolute finger position) is what makes fine adjustment work:
                        // one pixel of movement is one pixel of time, wherever you grabbed.
                        val deltaMs = (drag.y * msPerPx).toLong()
                        val proposed = currentScrubMs - deltaMs
                        val clamped = proposed.coerceIn(currentOldestMs, currentViewEndMs)
                        currentOnScrub(clamped)
                        currentOnTouchPreview(clamped)
                        currentOnTouchPositionChanged(
                            ((currentViewEndMs - clamped).toFloat() / currentRangeMs)
                                .coerceIn(0f, 1f),
                        )
                        // At the edges Frigate auto-scrolls the timeline so a long drag can keep
                        // going past the visible window; forward the leftover there.
                        if (proposed != clamped) currentOnPan(deltaMs)
                    }
                },
        )

        // ── Zoom buttons ──
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        ) {
            IconButton(
                onClick = { onZoomChange(0.5f) },
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), CircleShape),
            ) {
                Icon(
                    Icons.Filled.ZoomIn,
                    "Zoom in",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = { onZoomChange(2f) },
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), CircleShape),
            ) {
                Icon(
                    Icons.Filled.ZoomOut,
                    "Zoom out",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Touch target for the scrub handle. Material's 48 dp minimum, centred on the red line. */
private val ScrubHandleTouchHeight = 48.dp

internal fun fmtGridTime(
    epochMs: Long,
    showDay: Boolean = false,
): String {
    val ldt =
        Instant
            .fromEpochMilliseconds(epochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    val h12 = ldt.hour % 12
    val dh = if (h12 == 0) 12 else h12
    return if (showDay) {
        val day =
            ldt.dayOfWeek.name
                .take(3)
                .lowercase()
                .replaceFirstChar { it.uppercase() }
        val ap = if (ldt.hour < 12) "a" else "p"
        "$day $dh$ap"
    } else {
        val ap = if (ldt.hour < 12) "AM" else "PM"
        String.format(Locale.US, "%d:%02d %s", dh, ldt.minute, ap)
    }
}

internal fun fmtScrubberTimeLong(epochMs: Long): String {
    val ldt =
        Instant
            .fromEpochMilliseconds(epochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    val h12 = ldt.hour % 12
    val dh = if (h12 == 0) 12 else h12
    val ap = if (ldt.hour < 12) "AM" else "PM"
    return String.format(Locale.US, "%d:%02d:%02d %s", dh, ldt.minute, ldt.second, ap)
}
