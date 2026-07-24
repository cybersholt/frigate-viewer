package net.triton.frigateviewer.feature.review

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import net.triton.frigateviewer.core.model.MotionActivity
import net.triton.frigateviewer.core.model.ReviewSegment
import net.triton.frigateviewer.feature.events.TimelinePalette
import net.triton.frigateviewer.feature.events.TimelineScrubberColor
import net.triton.frigateviewer.feature.events.TimelineSeveritySignificantMotionColor
import net.triton.frigateviewer.feature.events.fmtGridTime
import net.triton.frigateviewer.feature.events.fmtScrubberTimeLong
import net.triton.frigateviewer.feature.events.severityColor

/** Width of the activity strip running down the right edge of the rail. */
private val StripWidth = 14.dp

/** Gap between the tick ruler and the activity strip. */
private val StripGap = 4.dp

private val MajorTickLength = 10.dp
private val MinorTickLength = 4.dp
private val LabelInset = 2.dp

/** A review item shorter than this still gets a visible mark rather than a sub-pixel sliver. */
private val MinPillHeight = 3.dp

/** Half the vertical distance between the handle's two bracket rules. */
private val HandleHalfHeight = 10.dp

/** Target bucket height for the activity waveform; the bucket count is derived from rail height. */
private val ActivityBucketHeight = 2.dp

private const val MIN_ACTIVITY_BUCKETS = 32
private const val MAX_ACTIVITY_BUCKETS = 400

/** Below this normalised intensity a bar is noise, not signal, and is skipped entirely. */
private const val ACTIVITY_NOISE_FLOOR = 0.04f

/**
 * Review's vertical rail: a time ruler on the left, an activity strip on the right, and a bracketed
 * scrub handle across both.
 *
 * Deliberately a different visual from Explore's
 * [net.triton.frigateviewer.feature.events.drawActivityTimeline]. Frigate's own frontend also
 * splits these in two — `MotionReviewTimeline` (Explore: a motion waveform you scrub against) vs
 * `EventReviewTimeline` (Review: discrete review items you jump between) — because they answer
 * different questions. This rail carries both, in separate lanes: the amber waveform is *context*
 * (when was there motion at all) and the severity-coloured pills are the *items* the grid beside it
 * is listing.
 *
 * Lives here rather than in `feature/events/TimelineRenderer.kt` (where it started) so it can use
 * [timelineTicks] and [activityIntensities] without the events package having to depend back on
 * review.
 *
 * All geometry derives from [size] and dp constants — [DrawScope] is a `Density`, so dp converts
 * correctly here. The previous version hardcoded raw-pixel sizes, which rendered ~5.7 dp tall text
 * on a 420 dpi phone; that, not the layout, was most of why the rail read as unreadable.
 *
 * [viewEndMs] anchors y=0 (top); time runs into the past downward, matching every other timeline
 * in the app.
 */
internal fun DrawScope.drawReviewRail(
    reviewSegments: List<ReviewSegment>,
    motionActivity: List<MotionActivity>,
    scrubberTimeMs: Long,
    viewEndMs: Long,
    rangeMs: Long,
    timeRangeHours: Float,
    labelPaint: android.graphics.Paint,
    handlePaint: android.graphics.Paint,
    palette: TimelinePalette,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f || rangeMs <= 0L) return

    val oldestMs = viewEndMs - rangeMs
    val msPerPx = rangeMs.toFloat() / h

    fun timeToY(timeMs: Long): Float = (viewEndMs - timeMs).toFloat() / msPerPx

    val stripW = StripWidth.toPx()
    val stripLeft = w - stripW
    val stripCentre = stripLeft + stripW / 2f
    val tickRight = stripLeft - StripGap.toPx()

    // ── Activity strip track ──────────────────────────────────────────────────
    drawRect(
        color = palette.gridLine.copy(alpha = 0.22f),
        topLeft = Offset(stripLeft, 0f),
        size = Size(stripW, h),
    )

    // ── Motion waveform inside the strip ──────────────────────────────────────
    val numBuckets =
        (h / ActivityBucketHeight.toPx())
            .toInt()
            .coerceIn(MIN_ACTIVITY_BUCKETS, MAX_ACTIVITY_BUCKETS)
    val intensities =
        activityIntensities(
            motion = motionActivity,
            fallbackSegments = reviewSegments,
            viewEndMs = viewEndMs,
            rangeMs = rangeMs,
            numBuckets = numBuckets,
        )
    val bucketPx = h / numBuckets.toFloat()
    val maxBarHalfWidth = stripW / 2f - 1.dp.toPx()
    intensities.forEachIndexed { i, intensity ->
        if (intensity < ACTIVITY_NOISE_FLOOR) return@forEachIndexed
        val halfW = maxBarHalfWidth * intensity
        val y = i * bucketPx + bucketPx / 2f
        drawLine(
            color = TimelineSeveritySignificantMotionColor.copy(alpha = 0.85f),
            start = Offset(stripCentre - halfW, y),
            end = Offset(stripCentre + halfW, y),
            strokeWidth = bucketPx.coerceAtLeast(1f),
            cap = StrokeCap.Round,
        )
    }

    // ── Ruler: dense minor ticks, longer labelled major ticks ─────────────────
    val ticks = timelineTicks(timeRangeHours)
    val majorTickLen = MajorTickLength.toPx()
    val minorTickLen = MinorTickLength.toPx()
    var tickMs = (viewEndMs / ticks.minorMs) * ticks.minorMs
    while (tickMs >= oldestMs) {
        val y = timeToY(tickMs)
        if (y in 0f..h) {
            val isMajor = tickMs % ticks.majorMs == 0L
            val len = if (isMajor) majorTickLen else minorTickLen
            drawLine(
                color = if (isMajor) palette.gridLine else palette.gridLine.copy(alpha = 0.45f),
                start = Offset(tickRight - len, y),
                end = Offset(tickRight, y),
                strokeWidth = if (isMajor) 1.2f else 0.8f,
            )
        }
        tickMs -= ticks.minorMs
    }

    // ── Labels on the major ticks ─────────────────────────────────────────────
    val showDayLabel = timeRangeHours > 24f
    val labelX = LabelInset.toPx()
    var labelMs = (viewEndMs / ticks.majorMs) * ticks.majorMs
    while (labelMs >= oldestMs) {
        val y = timeToY(labelMs)
        if (y in 0f..h) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(
                    fmtGridTime(labelMs, showDayLabel),
                    labelX,
                    (y - 3.dp.toPx()).coerceAtLeast(labelPaint.textSize),
                    labelPaint,
                )
            }
        }
        labelMs -= ticks.majorMs
    }

    // ── Review items as pills on the strip ────────────────────────────────────
    val minPillH = MinPillHeight.toPx()
    reviewSegments.forEach { segment ->
        val startMs = (segment.startTime * 1000).toLong()
        // A null end_time means the segment is still in progress — Frigate's own UI runs those to
        // "now", so the pill grows with the event instead of collapsing to a dot.
        val endMs = segment.endTime?.let { (it * 1000).toLong() } ?: viewEndMs
        if (endMs < oldestMs || startMs > viewEndMs) return@forEach

        val top = timeToY(endMs.coerceAtMost(viewEndMs)).coerceIn(0f, h)
        val bottom = timeToY(startMs.coerceAtLeast(oldestMs)).coerceIn(0f, h)
        val pillH = (bottom - top).coerceAtLeast(minPillH)
        drawRoundRect(
            color = severityColor(segment.severity, segment.hasBeenReviewed),
            topLeft = Offset(stripLeft, top.coerceAtMost((h - pillH).coerceAtLeast(0f))),
            size = Size(stripW, pillH),
            cornerRadius = CornerRadius(stripW / 2f, stripW / 2f),
        )
    }

    // ── Bracketed scrub handle ────────────────────────────────────────────────
    val scrubY = timeToY(scrubberTimeMs).coerceIn(0f, h)
    val halfH = HandleHalfHeight.toPx()
    val ruleWidth = 1.5.dp.toPx()
    listOf(scrubY - halfH, scrubY + halfH).forEach { ruleY ->
        val clamped = ruleY.coerceIn(0f, h)
        drawLine(
            color = TimelineScrubberColor,
            start = Offset(0f, clamped),
            end = Offset(w, clamped),
            strokeWidth = ruleWidth,
            cap = StrokeCap.Round,
        )
    }

    // Capsule is left-aligned on purpose: centred, it would sit on top of the activity strip and
    // hide the very thing the handle is being dragged against.
    val pillText = fmtScrubberTimeLong(scrubberTimeMs)
    val capsulePadX = 5.dp.toPx()
    val capsuleW = (handlePaint.measureText(pillText) + capsulePadX * 2).coerceAtMost(w)
    val capsuleH = handlePaint.textSize + 4.dp.toPx()
    val capsuleLeft = LabelInset.toPx().coerceAtMost((w - capsuleW).coerceAtLeast(0f))
    val capsuleTop = (scrubY - capsuleH / 2f).coerceIn(0f, (h - capsuleH).coerceAtLeast(0f))
    val capsulePaint =
        android.graphics.Paint().apply {
            color = TimelineScrubberColor.toArgb()
            isAntiAlias = true
        }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRoundRect(
            capsuleLeft,
            capsuleTop,
            capsuleLeft + capsuleW,
            capsuleTop + capsuleH,
            capsuleH / 2f,
            capsuleH / 2f,
            capsulePaint,
        )
        canvas.nativeCanvas.drawText(
            pillText,
            capsuleLeft + capsulePadX,
            capsuleTop + capsuleH / 2f + handlePaint.textSize * 0.36f,
            handlePaint,
        )
    }
}
