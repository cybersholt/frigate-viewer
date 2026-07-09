package net.triton.frigateviewer.feature.events

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.triton.frigateviewer.core.model.RecordingGap
import net.triton.frigateviewer.core.model.ReviewSegment
import java.util.Locale
import kotlin.math.abs

private val TimelineBackground = Color(0xFF121212)
private val GapColor = Color(0xFF000000)
private val SeverityAlertColor = Color(0xFFEF4444)
private val SeverityDetectionColor = Color(0xFFF59E0B)
private val SeveritySignificantMotionColor = Color(0xFFA16207)
private val ScrubberColor = Color(0xFFE53935)
private val GridLineColor = Color(0xFF333333)

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
    scrubberTimeMs: Long,
    timeRangeHours: Float,
    viewEndMs: Long,
    onScrub: (Long) -> Unit,
    onPan: (Long) -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rangeMs = (timeRangeHours * 3_600_000L).toLong()
    val oldestMs = viewEndMs - rangeMs
    // rememberUpdatedState so pointerInput(Unit) always reads latest values without restarting
    val currentViewEndMs by rememberUpdatedState(viewEndMs)
    val currentRangeMs by rememberUpdatedState(rangeMs)
    val currentOldestMs by rememberUpdatedState(oldestMs)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnPan by rememberUpdatedState(onPan)

    val labelPaint =
        remember {
            android.graphics.Paint().apply {
                textSize = 26f
                color = android.graphics.Color.argb(150, 255, 255, 255)
                isAntiAlias = true
            }
        }

    Box(modifier.background(TimelineBackground)) {
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
                            lastY = change.position.y
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val centerX = w / 2f
            val msPerPx = rangeMs.toFloat() / h
            val maxBarHalfWidth = w * 0.38f

            // viewEndMs → y=0 (top); older time → larger y (further down)
            fun timeToY(timeMs: Long): Float = (viewEndMs - timeMs).toFloat() / msPerPx

            // ── Recording-availability track: darken ranges with no footage ──
            recordingGaps.forEach { gap ->
                val gapStartMs = (gap.startTime * 1000).toLong()
                val gapEndMs = (gap.endTime * 1000).toLong()
                if (gapEndMs < oldestMs || gapStartMs > viewEndMs) return@forEach
                val top = timeToY(gapEndMs.coerceAtMost(viewEndMs))
                val bottom = timeToY(gapStartMs.coerceAtLeast(oldestMs))
                drawRect(
                    color = GapColor.copy(alpha = 0.5f),
                    topLeft = Offset(0f, top.coerceIn(0f, h)),
                    size =
                        androidx.compose.ui.geometry
                            .Size(w, (bottom - top).coerceIn(0f, h)),
                )
            }

            // ── Bucket review segments into severity-ranked activity density ──
            val numBuckets = 160
            val bucketMs = rangeMs / numBuckets
            val buckets = IntArray(numBuckets)
            val bucketSeverityRank = IntArray(numBuckets)
            reviewSegments.forEach { seg ->
                val segMs = (seg.startTime * 1000).toLong()
                if (segMs in oldestMs..viewEndMs) {
                    val idx = ((viewEndMs - segMs) / bucketMs).toInt().coerceIn(0, numBuckets - 1)
                    buckets[idx]++
                    val rank =
                        when (seg.severity) {
                            "alert" -> 3
                            "detection" -> 2
                            else -> 1
                        }
                    if (rank > bucketSeverityRank[idx]) bucketSeverityRank[idx] = rank
                }
            }
            val bucketPx = h / numBuckets.toFloat()

            // Smooth with 1/(1+k²) kernel (radius 3) for silky peak→trough transitions
            val smoothed =
                FloatArray(numBuckets) { i ->
                    var sum = 0f
                    var wsum = 0f
                    for (k in -3..3) {
                        val w = 1f / (1f + k * k.toFloat())
                        sum += buckets[(i + k).coerceIn(0, numBuckets - 1)] * w
                        wsum += w
                    }
                    sum / wsum
                }
            val maxSmoothed = smoothed.max().coerceAtLeast(1f)
            val minHalfW = 3f // baseline bar visible everywhere

            // ── Activity bars: width ∝ smoothed intensity, color ∝ worst severity in bucket ──
            smoothed.forEachIndexed { i, value ->
                val intensity = value / maxSmoothed
                val halfW = (maxBarHalfWidth * intensity).coerceAtLeast(minHalfW)
                val alpha = if (intensity < 0.02f) 0.22f else 0.9f
                val barY = i * bucketPx + bucketPx / 2f
                val barColor =
                    when (bucketSeverityRank[i]) {
                        3 -> SeverityAlertColor
                        2 -> SeverityDetectionColor
                        else -> SeveritySignificantMotionColor
                    }
                drawLine(
                    color = barColor.copy(alpha = alpha),
                    start = Offset(centerX - halfW, barY),
                    end = Offset(centerX + halfW, barY),
                    strokeWidth = bucketPx.coerceIn(2f, 5f),
                    cap = StrokeCap.Round,
                )
            }

            // ── Time grid lines + labels ──
            val intervalMs =
                when {
                    timeRangeHours <= 0.5f -> 2 * 60_000L
                    timeRangeHours <= 2f -> 5 * 60_000L
                    timeRangeHours <= 6f -> 15 * 60_000L
                    timeRangeHours <= 24f -> 60 * 60_000L
                    timeRangeHours <= 72f -> 4 * 3_600_000L
                    else -> 12 * 3_600_000L
                }
            val showDayLabel = timeRangeHours > 24f
            var gridMs = (viewEndMs / intervalMs) * intervalMs
            while (gridMs >= oldestMs) {
                val y = timeToY(gridMs)
                if (y in 0f..h) {
                    drawLine(GridLineColor, Offset(0f, y), Offset(w, y), 0.5f)
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                            fmtGridTime(gridMs, showDayLabel),
                            10f,
                            (y - 5f).coerceAtLeast(labelPaint.textSize),
                            labelPaint,
                        )
                    }
                }
                gridMs -= intervalMs
            }

            // ── Dotted centre vertical line ──
            var dotY = 0f
            while (dotY < h) {
                drawLine(
                    Color.White.copy(alpha = 0.18f),
                    Offset(centerX, dotY),
                    Offset(centerX, (dotY + 5f).coerceAtMost(h)),
                    1f,
                )
                dotY += 10f
            }

            // ── Movable scrubber at the selected time ──
            val scrubY = timeToY(scrubberTimeMs).coerceIn(0f, h)
            drawLine(
                ScrubberColor,
                Offset(0f, scrubY),
                Offset(w, scrubY),
                2.5f,
                cap = StrokeCap.Round,
            )

            // ── Time pill that follows the scrubber ──
            val pillText = fmtScrubberTimeLong(scrubberTimeMs)
            val pillPaint =
                android.graphics.Paint().apply {
                    textSize = 26f
                    color = android.graphics.Color.WHITE
                    isAntiAlias = true
                }
            val pillPad = 8f
            val pillH = pillPaint.textSize + pillPad * 2
            val pillW = pillPaint.measureText(pillText) + pillPad * 2
            val pillLeft = centerX - pillW / 2
            val pillTop = (scrubY - pillH - 4f).coerceIn(0f, h - pillH)
            val bgPaint =
                android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(220, 229, 57, 53)
                    isAntiAlias = true
                }
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRoundRect(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH, 8f, 8f, bgPaint)
                canvas.nativeCanvas.drawText(pillText, pillLeft + pillPad, pillTop + pillPad + pillPaint.textSize * 0.85f, pillPaint)
            }
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
