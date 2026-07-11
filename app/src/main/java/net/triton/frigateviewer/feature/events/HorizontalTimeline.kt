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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.triton.frigateviewer.core.model.MotionActivity
import net.triton.frigateviewer.core.model.RecordingGap
import net.triton.frigateviewer.core.model.ReviewSegment
import java.util.Locale
import kotlin.math.abs

private val TimelineBackground = Color(0xFF121212)

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
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnPan by rememberUpdatedState(onPan)
    val currentOnTouchPreview by rememberUpdatedState(onTouchPreview)
    val currentOnTouchPositionChanged by rememberUpdatedState(onTouchPositionChanged)

    val labelPaint =
        remember {
            android.graphics.Paint().apply {
                textSize = 26f
                color = android.graphics.Color.argb(150, 255, 255, 255)
                isAntiAlias = true
            }
        }

    BoxWithConstraints(modifier.background(TimelineBackground)) {
        fun timeAtFraction(frac: Float): Long =
            (currentViewEndMs - frac * currentRangeMs).toLong().coerceIn(currentOldestMs, currentViewEndMs)

        Canvas(
            Modifier
                .fillMaxSize()
                // Unit key: gesture handler never restarts mid-drag when zoom/pan changes
                .pointerInput(Unit) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var isPanning = false
                        var lastY = down.position.y
                        currentOnTouchPreview(timeAtFraction(down.position.y / size.height))
                        currentOnTouchPositionChanged(down.position.y / size.height)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                if (!isPanning) {
                                    val frac = change.position.y / size.height
                                    currentOnScrub(
                                        (currentViewEndMs - frac * currentRangeMs)
                                            .toLong()
                                            .coerceIn(currentOldestMs, currentViewEndMs),
                                    )
                                }
                                break
                            }
                            val dy = change.position.y - lastY
                            if (!isPanning && abs(change.position.y - down.position.y) > touchSlop) {
                                isPanning = true
                            }
                            if (isPanning) {
                                change.consume()
                                val msPerPx = currentRangeMs.toFloat() / size.height
                                // Content follows the finger: drag down reveals newer time
                                // (toward now), drag up reveals further into the past.
                                currentOnPan((dy * msPerPx).toLong())
                            }
                            currentOnTouchPreview(timeAtFraction(change.position.y / size.height))
                            currentOnTouchPositionChanged(change.position.y / size.height)
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
            )
        }

        // ── Zoom buttons ──
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        ) {
            IconButton(
                onClick = { onZoomChange(0.5f) },
                modifier = Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(Icons.Filled.ZoomIn, "Zoom in", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = { onZoomChange(2f) },
                modifier = Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(Icons.Filled.ZoomOut, "Zoom out", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

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
