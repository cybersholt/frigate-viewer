package net.triton.frigateviewer.feature.events

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import net.triton.frigateviewer.core.model.MotionActivity
import net.triton.frigateviewer.core.model.RecordingGap
import net.triton.frigateviewer.core.model.ReviewSegment

// Exact values from Frigate's own default theme (web/themes/theme-default.css: --severity_alert
// = red-800, --severity_detection = orange-600, --severity_significant_motion = yellow-400,
// resolved against web/themes/tailwind-base.css). Matching upstream's literal hues, not our own
// palette, is the point — see the class doc below.
internal val TimelineSeverityAlertColor = Color(0xFF991B1B)
internal val TimelineSeverityDetectionColor = Color(0xFFEA580C)
internal val TimelineSeveritySignificantMotionColor = Color(0xFFFACC15)

// "Dimmed" variants (Frigate's red-500 / orange-400 / yellow-200) — used for already-reviewed
// review items on the severity rail, same as EventSegment.tsx's `severityColors[n]` when reviewed.
internal val TimelineSeverityAlertDimmedColor = Color(0xFFEF4444)
internal val TimelineSeverityDetectionDimmedColor = Color(0xFFFB923C)
internal val TimelineSeveritySignificantMotionDimmedColor = Color(0xFFFEF08A)

internal val TimelineScrubberColor = Color(0xFFE53935)

internal fun severityColor(
    severity: String,
    reviewed: Boolean = false,
): Color =
    when (severity) {
        "alert" -> if (reviewed) TimelineSeverityAlertDimmedColor else TimelineSeverityAlertColor
        "detection" -> if (reviewed) TimelineSeverityDetectionDimmedColor else TimelineSeverityDetectionColor
        else -> if (reviewed) TimelineSeveritySignificantMotionDimmedColor else TimelineSeveritySignificantMotionColor
    }

/**
 * The parts of the timeline that must invert between light and dark themes.
 *
 * The severity hues and the scrubber above are deliberately NOT in here: they encode meaning
 * (alert / detection / motion), match Frigate's own web frontend, and read correctly against both
 * a light and a dark surface. Everything below is surface-or-ink, and was previously hardcoded to
 * dark (a #121212 background with white labels) — which is why the timeline stayed dark in light
 * mode.
 */
internal data class TimelinePalette(
    val background: Color,
    val gridLine: Color,
    /** Overlay marking spans with no recording. Alpha is baked in — drawn as-is. */
    val gap: Color,
    val centreLine: Color,
    val labelArgb: Int,
)

@Composable
internal fun rememberTimelinePalette(): TimelinePalette {
    val cs = MaterialTheme.colorScheme
    return remember(cs) {
        TimelinePalette(
            background = cs.surfaceContainerLow,
            gridLine = cs.outlineVariant,
            gap = cs.onSurface.copy(alpha = 0.35f),
            centreLine = cs.onSurface.copy(alpha = 0.18f),
            labelArgb = cs.onSurface.copy(alpha = 0.60f).toArgb(),
        )
    }
}

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
    motionActivity: List<MotionActivity> = emptyList(),
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
    palette: TimelinePalette,
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
            color = palette.gap,
            topLeft = Offset(0f, top.coerceIn(0f, h)),
            size = Size(w, (bottom - top).coerceIn(0f, h)),
        )
    }

    // ── Bucket review segments by worst severity (color only, not width) ──
    val bucketMs = rangeMs / numBuckets
    val bucketSeverityRank = IntArray(numBuckets)
    reviewSegments.forEach { seg ->
        val segMs = (seg.startTime * 1000).toLong()
        if (segMs in oldestMs..viewEndMs) {
            val idx = ((viewEndMs - segMs) / bucketMs).toInt().coerceIn(0, numBuckets - 1)
            val rank =
                when (seg.severity) {
                    "alert" -> 3
                    "detection" -> 2
                    else -> 1
                }
            if (rank > bucketSeverityRank[idx]) bucketSeverityRank[idx] = rank
        }
    }

    // ── Bucket activity intensity: real per-bucket motion score (api/review/activity/motion)
    // when available; falls back to review-segment density if the endpoint returned nothing
    // (older Frigate version, or data not yet loaded). ──
    val buckets = FloatArray(numBuckets)
    if (motionActivity.isNotEmpty()) {
        motionActivity.forEach { point ->
            val pointMs = (point.startTime * 1000).toLong()
            if (pointMs in oldestMs..viewEndMs) {
                val idx = ((viewEndMs - pointMs) / bucketMs).toInt().coerceIn(0, numBuckets - 1)
                val motionValue = point.motion.toFloat()
                if (motionValue > buckets[idx]) buckets[idx] = motionValue
            }
        }
    } else {
        reviewSegments.forEach { seg ->
            val segMs = (seg.startTime * 1000).toLong()
            if (segMs in oldestMs..viewEndMs) {
                val idx = ((viewEndMs - segMs) / bucketMs).toInt().coerceIn(0, numBuckets - 1)
                buckets[idx] += 1f
            }
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
            drawLine(palette.gridLine, Offset(0f, y), Offset(w, y), 0.5f)
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
            palette.centreLine,
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
    val pillLeft = (centerX - pillW / 2).coerceIn(0f, (w - pillW).coerceAtLeast(0f))
    val pillTop = (scrubY - pillH - 4f).coerceIn(0f, (h - pillH).coerceAtLeast(0f))
    val bgPaint =
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(220, 229, 57, 53)
            isAntiAlias = true
        }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRoundRect(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH, 8f, 8f, bgPaint)
        canvas.nativeCanvas.drawText(
            pillText,
            pillLeft + pillPad,
            pillTop + pillPad + pillPaint.textSize * 0.85f,
            pillPaint,
        )
    }
}

/**
 * Review page's dedicated severity rail — visually distinct from [drawActivityTimeline] on
 * purpose. Frigate's own frontend renders these with two different components:
 * `MotionReviewTimeline`/`MotionSegment` (Explore/History — thin motion-intensity ticks with a
 * faint severity wash) vs `EventReviewTimeline`/`EventSegment` (Review — solid rounded pills, one
 * per review item, spanning its actual start→end duration, full-saturation color, dimmed once
 * reviewed). This draws the latter: no motion waveform, no recording-gap shading — Review's
 * upstream component doesn't take that data either.
 *
 * [viewEndMs] anchors y=0 (top); past scrolls downward, matching [drawActivityTimeline]'s convention.
 */
internal fun DrawScope.drawSeverityEventTimeline(
    reviewSegments: List<ReviewSegment>,
    scrubberTimeMs: Long,
    viewEndMs: Long,
    rangeMs: Long,
    timeRangeHours: Float,
    centerX: Float,
    pillHalfWidth: Float,
    labelPaint: android.graphics.Paint,
    drawLabels: Boolean,
    labelX: Float,
    palette: TimelinePalette,
) {
    val w = size.width
    val h = size.height
    val oldestMs = viewEndMs - rangeMs
    val msPerPx = rangeMs.toFloat() / h
    val dotRadius = pillHalfWidth.coerceAtMost(10f)

    fun timeToY(timeMs: Long): Float = (viewEndMs - timeMs).toFloat() / msPerPx

    // ── One dot per review item at its start time — matches Frigate's own Review rail, which
    // marks a point in time rather than spanning a duration bar (confirmed against a live
    // screenshot of the reference PWA: small fixed-size circles, not variable-height bars). ──
    reviewSegments.forEach { seg ->
        val startMs = (seg.startTime * 1000).toLong()
        if (startMs < oldestMs || startMs > viewEndMs) return@forEach
        drawCircle(
            color = severityColor(seg.severity, seg.hasBeenReviewed),
            radius = dotRadius,
            center = Offset(centerX, timeToY(startMs)),
        )
    }

    // ── Time grid lines + labels, with finer unlabeled ticks between them (ruler look, matching
    // the reference) ──
    val intervalMs =
        when {
            timeRangeHours <= 0.5f -> 2 * 60_000L
            timeRangeHours <= 2f -> 15 * 60_000L
            timeRangeHours <= 6f -> 30 * 60_000L
            timeRangeHours <= 24f -> 60 * 60_000L
            timeRangeHours <= 72f -> 4 * 3_600_000L
            else -> 12 * 3_600_000L
        }
    val minorIntervalMs = intervalMs / 5
    val showDayLabel = timeRangeHours > 24f

    var minorMs = (viewEndMs / minorIntervalMs) * minorIntervalMs
    while (minorMs >= oldestMs) {
        val y = timeToY(minorMs)
        if (y in 0f..h) {
            val tickLen = if (minorMs % intervalMs == 0L) w else w * 0.35f
            drawLine(palette.gridLine, Offset(w - tickLen, y), Offset(w, y), 0.5f)
        }
        minorMs -= minorIntervalMs
    }

    var gridMs = (viewEndMs / intervalMs) * intervalMs
    while (gridMs >= oldestMs) {
        val y = timeToY(gridMs)
        if (y in 0f..h && drawLabels) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(
                    fmtGridTime(gridMs, showDayLabel),
                    labelX,
                    (y - 5f).coerceAtLeast(labelPaint.textSize),
                    labelPaint,
                )
            }
        }
        gridMs -= intervalMs
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
}
