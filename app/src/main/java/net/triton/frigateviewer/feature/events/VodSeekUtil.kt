package net.triton.frigateviewer.feature.events

import net.triton.frigateviewer.core.model.RecordingSegment

/**
 * Ported from Frigate's web/src/utils/videoUtil.ts (calculateInpointOffset / calculateSeekPosition),
 * verified against blakeblackshear/frigate source. Frigate's
 * `/vod/<camera>/start/<after>/end/<before>/master.m3u8` concatenates whatever recording segments
 * exist in that range and trims the first segment to begin exactly at `after` — so player-seconds
 * is NOT a simple `wallClockTime - after` offset whenever recording has gaps (motion-only capture,
 * camera downtime) or the first segment predates `after`. Both must be derived from the actual
 * segment list, or seeking lands on the wrong frame / a frozen picture.
 */
object VodSeekUtil {
    fun calculateInpointOffset(
        rangeStart: Double?,
        firstSegment: RecordingSegment?,
    ): Double {
        if (rangeStart == null || firstSegment == null) return 0.0
        return if (firstSegment.startTime < rangeStart && firstSegment.endTime > rangeStart) {
            rangeStart - firstSegment.startTime
        } else {
            0.0
        }
    }

    /**
     * Player-seconds for [timestamp], found by summing segment durations up to it — the VOD is a
     * concatenation of segments, not a single continuous stream, so gaps must not be counted.
     * Returns null if [timestamp] falls outside the fetched [recordings] window.
     */
    fun calculateSeekPosition(
        timestamp: Double,
        recordings: List<RecordingSegment>,
        inpointOffset: Double = 0.0,
    ): Double? {
        if (recordings.isEmpty()) return null
        if (timestamp < recordings.first().startTime || timestamp > recordings.last().endTime) return null

        var seekSeconds = 0.0
        for (segment in recordings) {
            if (segment.startTime > timestamp) break
            seekSeconds +=
                if (segment.endTime < timestamp) {
                    segment.endTime - segment.startTime
                } else {
                    segment.endTime - segment.startTime - (segment.endTime - timestamp)
                }
        }
        seekSeconds -= inpointOffset
        return if (seekSeconds >= 0) seekSeconds else null
    }

    /** Hour-aligned (UTC) chunk containing [epochSec], capped at "now" — mirrors getChunkedTimeDay. */
    fun hourChunkFor(epochSec: Long): LongRange {
        val after = (epochSec / 3600L) * 3600L
        val nowSec = System.currentTimeMillis() / 1000L
        val nextHour = after + 3600L
        val before = if (nextHour > nowSec) nowSec else nextHour
        return after..before
    }
}
