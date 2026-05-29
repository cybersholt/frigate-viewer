package net.triton.frigateviewer.feature.events

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.triton.frigateviewer.core.model.FrigateEvent
import java.util.Locale

private val FIVE_MIN_MS = 5 * 60_000L
private val FIFTEEN_MIN_MS = 15 * 60_000L
private val ONE_HOUR_MS = 3_600_000L

/**
 * Narrow vertical timeline strip for the Events screen right edge.
 *
 * Ruler-style ticks (5/15-min/hour intervals), red event blocks at actual event
 * timestamps, and a red scrubber that bidirectionally syncs with [gridState].
 */
@Composable
fun TimelinePanel(
    events: List<FrigateEvent>,
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
    val startMs = nowMs - (timeRangeHours * 3_600_000L).toLong()
    val rangeMs = (nowMs - startMs).toFloat()

    // Grid scroll → scrubber: when the user scrolls the list, move the scrubber to match
    LaunchedEffect(gridState.firstVisibleItemIndex) {
        if (events.isNotEmpty()) {
            val idx = gridState.firstVisibleItemIndex.coerceIn(0, events.size - 1)
            val timeMs = (events[idx].startTime * 1000).toLong()
            onScrub(timeMs)
        }
    }

    val scrubberFrac = ((scrubberTimeMs - startMs) / rangeMs).coerceIn(0f, 1f)

    val axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val tickColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val eventColor = MaterialTheme.colorScheme.error
    val scrubberColor = MaterialTheme.colorScheme.error
    val labelTextColor = MaterialTheme.colorScheme.onSurface

    Column(modifier) {
        IconButton(
            onClick = onZoomIn,
            modifier = Modifier.size(32.dp).align(Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in", modifier = Modifier.size(18.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                                    val frac =
                                        1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                    val timeMs = startMs + (frac * rangeMs).toLong()
                                    onScrub(timeMs)
                                    val nearestIdx =
                                        events.indexOfFirst {
                                            (it.startTime * 1000.0).toLong() <= timeMs
                                        }
                                    if (nearestIdx >= 0) {
                                        scope.launch { gridState.scrollToItem(nearestIdx) }
                                    }
                                }
                            }
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                // Axis line sits at 40% from left; ticks extend right; event blocks fill the rest
                val axisX = w * 0.40f
                val eventAreaLeft = axisX + 2f
                val eventAreaW = w - eventAreaLeft

                // ── Vertical ruler axis ──
                drawLine(axisColor, Offset(axisX, 0f), Offset(axisX, h), strokeWidth = 1f)

                // ── Tick marks ──
                val labelPaint =
                    android.graphics.Paint().apply {
                        textSize = 15f
                        color =
                            android.graphics.Color.argb(
                                (labelTextColor.alpha * 140).toInt(),
                                (labelTextColor.red * 255).toInt(),
                                (labelTextColor.green * 255).toInt(),
                                (labelTextColor.blue * 255).toInt(),
                            )
                        isAntiAlias = true
                    }

                // First 5-min tick at or after startMs
                var tickMs = (startMs / FIVE_MIN_MS) * FIVE_MIN_MS
                if (tickMs < startMs) tickMs += FIVE_MIN_MS

                while (tickMs <= nowMs) {
                    val frac = (tickMs - startMs).toFloat() / rangeMs
                    val y = h * (1f - frac)
                    val isHour = tickMs % ONE_HOUR_MS == 0L
                    val isQuarter = tickMs % FIFTEEN_MIN_MS == 0L
                    val tickLen =
                        when {
                            isHour -> w * 0.30f
                            isQuarter -> w * 0.18f
                            else -> w * 0.08f
                        }
                    val tickAlpha =
                        when {
                            isHour -> 0.65f
                            isQuarter -> 0.45f
                            else -> 0.25f
                        }
                    drawLine(
                        color = tickColor.copy(alpha = tickAlpha),
                        start = Offset(axisX, y),
                        end = Offset(axisX + tickLen, y),
                        strokeWidth = if (isHour) 0.8f else 0.5f,
                    )

                    if (isHour) {
                        val label = formatTimeShort(tickMs)
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawText(
                                label,
                                2f,
                                (y - 2f).coerceAtLeast(labelPaint.textSize),
                                labelPaint,
                            )
                        }
                    }
                    tickMs += FIVE_MIN_MS
                }

                // ── Event blocks ──
                events.forEach { evObj ->
                    val evMs = (evObj.startTime * 1000).toLong()
                    if (evMs < startMs || evMs > nowMs) return@forEach
                    val frac = (evMs - startMs).toFloat() / rangeMs
                    val y = h * (1f - frac)
                    // Duration as px height; minimum 4px so single-frame events are visible
                    val durMs =
                        evObj.endTime?.let { ((it - evObj.startTime) * 1000).toLong() } ?: 30_000L
                    val durPx = (durMs.toFloat() / rangeMs * h).coerceAtLeast(4f)
                    drawRect(
                        color = eventColor.copy(alpha = 0.75f),
                        topLeft = Offset(eventAreaLeft, y - durPx / 2f),
                        size = Size(eventAreaW, durPx),
                    )
                }

                // ── Scrubber line ──
                val scrubY = h * (1f - scrubberFrac)
                drawLine(
                    color = scrubberColor,
                    start = Offset(0f, scrubY),
                    end = Offset(w, scrubY),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
                // Diamond/circle at the axis intersection
                drawCircle(
                    color = scrubberColor,
                    radius = 4f,
                    center = Offset(axisX, scrubY),
                )
            }
        }

        IconButton(
            onClick = onZoomOut,
            modifier = Modifier.size(32.dp).align(Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out", modifier = Modifier.size(18.dp))
        }
    }
}

private fun formatTimeShort(epochMs: Long): String {
    val ldt =
        Instant
            .fromEpochMilliseconds(epochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    val h12 = ldt.hour % 12
    val displayH = if (h12 == 0) 12 else h12
    val ap = if (ldt.hour < 12) "a" else "p"
    return String.format(Locale.US, "%d%s", displayH, ap)
}
