package net.triton.frigateviewer.feature.events

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import net.triton.frigateviewer.core.model.RecordingGap
import net.triton.frigateviewer.core.model.ReviewSegment

internal val TimelineGapColor = Color(0xFF000000)
internal val TimelineSeverityAlertColor = Color(0xFFEF4444)
internal val TimelineSeverityDetectionColor = Color(0xFFF59E0B)
internal val TimelineSeveritySignificantMotionColor = Color(0xFFA16207)
internal val TimelineScrubberColor = Color(0xFFE53935)
internal val TimelineGridLineColor = Color(0xFF333333)

/**
 * Shared vertical-timeline visual: a severity-colored motion-activity waveform (bar width ∝
 * bucketed+smoothed activity density, color ∝ worst review severity in the bucket) drawn
 * symmetrically around [centerX], recording-gap darkening behind it, hour/interval grid lines
 * with labels, and a scrubber line + time pill. Mirrors Frigate's own web frontend
 * (MotionReviewTimeline). Used by both HorizontalTimeline (Event Detail, full width) and
 * TimelinePanel (Events grid sidebar strip, narrow width) so the two never drift apart again.
 *
 * [viewEndMs] anchors y=0 (top); past scrolls downward, same convention as both callers.
 */
internal fun DrawScope.drawActivityTimeline(
    reviewSegments: List<ReviewSegment>,
    recordingGaps: List<RecordingGap>,
    scrubberTimeMs: Long,
    viewEndMs: Long,
    rangeMs: Long,
    timeRangeHours: Float,
    centerX: Float,
    maxBarHalfWidth: Float,
    numBuckets: Int,
    labelPaint: android.graphics.Paint,
    drawLabels: Boolean,
    labelX: Float,
) {
    val w = size.width
    val h = size.height
    val oldestMs = viewEndMs - rangeMs
    val msPerPx = rangeMs.toFloat() / h

    fun timeToY(timeMs: Long): Float = (viewEndMs - timeMs).toFloat() / msPerPx

    // ── Recording-availability track: darken ranges with no footage ──
    recordingGaps.forEach { gap ->
        val gapStartMs = (gap.startTime * 1000).toLong()
        val gapEndMs = (gap.endTime * 1000).toLong()
        if (gapEndMs < oldestMs || gapStartMs > viewEndMs) return@forEach
        val top = timeToY(gapEndMs.coerceAtMost(viewEndMs))
        val bottom = timeToY(gapStartMs.coerceAtLeast(oldestMs))
        drawRect(
            color = TimelineGapColor.copy(alpha = 0.5f),
            topLeft = Offset(0f, top.coerceIn(0f, h)),
            size = Size(w, (bottom - top).coerceIn(0f, h)),
        )
    }

    // ── Bucket review segments into severity-ranked activity density ──
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
                val weight = 1f / (1f + k * k.toFloat())
                sum += buckets[(i + k).coerceIn(0, numBuckets - 1)] * weight
                wsum += weight
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
                3 -> TimelineSeverityAlertColor
                2 -> TimelineSeverityDetectionColor
                else -> TimelineSeveritySignificantMotionColor
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
            drawLine(TimelineGridLineColor, Offset(0f, y), Offset(w, y), 0.5f)
            if (drawLabels) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(
                        fmtGridTime(gridMs, showDayLabel),
                        labelX,
                        (y - 5f).coerceAtLeast(labelPaint.textSize),
                        labelPaint,
                    )
                }
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
        TimelineScrubberColor,
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
    val pillLeft = (centerX - pillW / 2).coerceIn(0f, w - pillW)
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
