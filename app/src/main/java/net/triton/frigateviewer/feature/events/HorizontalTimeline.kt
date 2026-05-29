package net.triton.frigateviewer.feature.events

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.triton.frigateviewer.core.model.FrigateEvent
import java.util.Locale

private val TimelineBackground = Color(0xFF121212)
private val BarColor = Color(0xFFEAA300)
private val ScrubberColor = Color(0xFFE53935)
private val GridLineColor = Color(0xFF333333)

/**
 * Full-screen vertical timeline for Event Detail — Timeline view.
 *
 * NOW is anchored at y=0 (top). Past scrolls downward — there is no way to
 * scroll above now. The red scrubber is a movable line that starts at the
 * event's detection time. Bar WIDTH represents activity density: more events
 * in a time bucket → wider bar.
 */
@Composable
fun HorizontalTimeline(
    events: List<FrigateEvent>,
    scrubberTimeMs: Long,
    timeRangeHours: Float,
    onScrub: (Long) -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // nowMs is the immovable ceiling — the top of the visible range
    val nowMs = remember { System.currentTimeMillis() }
    val rangeMs = (timeRangeHours * 3_600_000L).toLong()
    val oldestMs = nowMs - rangeMs
    // rememberUpdatedState so pointerInput(Unit) always reads latest values without restarting
    val currentRangeMs by rememberUpdatedState(rangeMs)
    val currentOldestMs by rememberUpdatedState(oldestMs)
    val currentOnScrub by rememberUpdatedState(onScrub)

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
                // Unit key: gesture handler never restarts mid-drag when zoom changes
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull() ?: continue
                            if (change.pressed) {
                                change.consume()
                                val frac = change.position.y / size.height
                                currentOnScrub((nowMs - frac * currentRangeMs).toLong().coerceIn(currentOldestMs, nowMs))
                            }
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val centerX = w / 2f
            val msPerPx = rangeMs.toFloat() / h
            val maxBarHalfWidth = w * 0.38f

            // nowMs → y=0 (top); older time → larger y (further down)
            fun timeToY(timeMs: Long): Float = (nowMs - timeMs).toFloat() / msPerPx

            // ── Bucket events into activity density ──
            val numBuckets = 160
            val bucketMs = rangeMs / numBuckets
            val buckets = IntArray(numBuckets)
            events.forEach { ev ->
                val evMs = (ev.startTime * 1000).toLong()
                if (evMs in oldestMs..nowMs) {
                    val idx = ((nowMs - evMs) / bucketMs).toInt().coerceIn(0, numBuckets - 1)
                    buckets[idx]++
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

            // ── Activity bars: width ∝ smoothed intensity, tiny bar for empty areas ──
            smoothed.forEachIndexed { i, value ->
                val intensity = value / maxSmoothed
                val halfW = (maxBarHalfWidth * intensity).coerceAtLeast(minHalfW)
                val alpha = if (intensity < 0.02f) 0.22f else 0.9f
                val barY = i * bucketPx + bucketPx / 2f
                drawLine(
                    color = BarColor.copy(alpha = alpha),
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
            var gridMs = (nowMs / intervalMs) * intervalMs
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
