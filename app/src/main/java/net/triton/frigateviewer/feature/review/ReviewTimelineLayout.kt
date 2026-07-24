package net.triton.frigateviewer.feature.review

import net.triton.frigateviewer.core.model.MotionActivity
import net.triton.frigateviewer.core.model.ReviewSegment

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L

/**
 * Radius of the 1/(1+k²) smoothing kernel applied to the activity waveform. Same kernel and radius
 * as Explore's [net.triton.frigateviewer.feature.events.drawActivityTimeline] so the two surfaces
 * render the same motion data with the same shape.
 */
private const val SMOOTHING_RADIUS = 3

/**
 * Tick spacing for the Review rail at one zoom level: [majorMs] gets a long tick plus a time label,
 * [minorMs] a short unlabelled one.
 */
data class TimelineTicks(
    val majorMs: Long,
    val minorMs: Long,
)

/**
 * Ruler spacing for a given rail zoom.
 *
 * The pairs are chosen so the minor tick is always a round, human-readable subdivision of the
 * major one (1 min under a 15 min label, 5 min under an hour) rather than a fixed divisor —
 * `majorMs / 5` would put minor ticks 12 minutes apart under an hour label, which reads as noise.
 * At the rail's 2 h default this yields 15-minute labels over 1-minute ticks, matching Frigate's
 * own PWA rail.
 */
fun timelineTicks(timeRangeHours: Float): TimelineTicks =
    when {
        timeRangeHours <= 0.5f -> TimelineTicks(majorMs = 5 * MINUTE_MS, minorMs = MINUTE_MS)
        timeRangeHours <= 1f -> TimelineTicks(majorMs = 10 * MINUTE_MS, minorMs = MINUTE_MS)
        timeRangeHours <= 2f -> TimelineTicks(majorMs = 15 * MINUTE_MS, minorMs = MINUTE_MS)
        timeRangeHours <= 4f -> TimelineTicks(majorMs = 30 * MINUTE_MS, minorMs = 5 * MINUTE_MS)
        timeRangeHours <= 8f -> TimelineTicks(majorMs = HOUR_MS, minorMs = 5 * MINUTE_MS)
        timeRangeHours <= 16f -> TimelineTicks(majorMs = 2 * HOUR_MS, minorMs = 15 * MINUTE_MS)
        timeRangeHours <= 36f -> TimelineTicks(majorMs = 4 * HOUR_MS, minorMs = 30 * MINUTE_MS)
        timeRangeHours <= 96f -> TimelineTicks(majorMs = 12 * HOUR_MS, minorMs = 2 * HOUR_MS)
        else -> TimelineTicks(majorMs = 24 * HOUR_MS, minorMs = 6 * HOUR_MS)
    }

/**
 * Normalised 0..1 motion intensity per bucket for the rail's activity strip.
 *
 * Bucket 0 is the *newest* slice ([viewEndMs]) and the last bucket the oldest, matching the rail's
 * top-down time order — get this backwards and the waveform silently renders mirrored, which looks
 * plausible and is exactly the kind of bug a screenshot won't settle.
 *
 * [motion] is the real per-bucket waveform from `api/review/activity/motion`. When it is empty —
 * older Frigate, or the request hasn't landed yet — [fallbackSegments] stands in, bucketed by
 * review-item density, so the strip degrades to "roughly when things happened" instead of going
 * blank. Output is scaled against its own peak, so a quiet window still fills the strip; the strip
 * shows *relative* activity within the visible range, not an absolute motion score.
 */
fun activityIntensities(
    motion: List<MotionActivity>,
    fallbackSegments: List<ReviewSegment>,
    viewEndMs: Long,
    rangeMs: Long,
    numBuckets: Int,
): FloatArray {
    if (numBuckets <= 0 || rangeMs <= 0L) return FloatArray(0)
    val oldestMs = viewEndMs - rangeMs

    fun bucketOf(timeMs: Long): Int? {
        if (timeMs < oldestMs || timeMs > viewEndMs) return null
        return (((viewEndMs - timeMs) * numBuckets) / rangeMs).toInt().coerceIn(0, numBuckets - 1)
    }

    val raw = FloatArray(numBuckets)
    if (motion.isNotEmpty()) {
        motion.forEach { point ->
            bucketOf((point.startTime * 1000).toLong())?.let { i ->
                raw[i] = maxOf(raw[i], point.motion.toFloat())
            }
        }
    } else {
        fallbackSegments.forEach { segment ->
            bucketOf((segment.startTime * 1000).toLong())?.let { i -> raw[i] += 1f }
        }
    }

    val smoothed =
        FloatArray(numBuckets) { i ->
            var sum = 0f
            var weightSum = 0f
            for (k in -SMOOTHING_RADIUS..SMOOTHING_RADIUS) {
                val weight = 1f / (1f + k * k.toFloat())
                sum += raw[(i + k).coerceIn(0, numBuckets - 1)] * weight
                weightSum += weight
            }
            sum / weightSum
        }

    val peak = smoothed.max()
    if (peak <= 0f) return FloatArray(numBuckets)
    return FloatArray(numBuckets) { smoothed[it] / peak }
}
