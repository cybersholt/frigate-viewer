package net.triton.frigateviewer.feature.review

import net.triton.frigateviewer.core.model.MotionActivity
import net.triton.frigateviewer.core.model.ReviewSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The ruler and activity-strip math behind the Review rail.
 *
 * Extracted out of the renderer for the same reason as `ZoomPanState`: none of this is checkable
 * from a screenshot. A mirrored waveform, a minor tick that isn't a round subdivision, or an
 * intensity scale that clips all look plausible on a phone and are obvious here.
 */
class ReviewTimelineLayoutTest {
    // ── timelineTicks ─────────────────────────────────────────────────────────

    @Test
    fun `the default 2 hour zoom rules 15 minute labels over 1 minute ticks`() {
        // The spacing read off Frigate's own PWA rail, which this was built to match.
        val ticks = timelineTicks(2f)
        assertEquals(15 * 60_000L, ticks.majorMs, "major")
        assertEquals(60_000L, ticks.minorMs, "minor")
    }

    @Test
    fun `every zoom subdivides its major tick exactly`() {
        // The renderer decides "is this a labelled tick?" with `tickMs % majorMs == 0L` while
        // stepping by minorMs. If minor doesn't divide major, major ticks get skipped entirely and
        // the rail loses its labels at that zoom.
        ZOOMS.forEach { hours ->
            val ticks = timelineTicks(hours)
            assertTrue(ticks.minorMs > 0L, "minor must be positive at ${hours}h")
            assertTrue(ticks.minorMs < ticks.majorMs, "minor must be finer than major at ${hours}h")
            assertEquals(0L, ticks.majorMs % ticks.minorMs, "minor must divide major at ${hours}h")
        }
    }

    @Test
    fun `tick spacing never tightens as the visible range grows`() {
        // Zooming out must not produce denser labels — that is how a ruler turns into a smear.
        ZOOMS.zipWithNext().forEach { (narrower, wider) ->
            val a = timelineTicks(narrower)
            val b = timelineTicks(wider)
            assertTrue(b.majorMs >= a.majorMs, "major shrank going ${narrower}h → ${wider}h")
            assertTrue(b.minorMs >= a.minorMs, "minor shrank going ${narrower}h → ${wider}h")
        }
    }

    // ── activityIntensities ───────────────────────────────────────────────────

    @Test
    fun `no data anywhere yields a flat empty strip rather than a crash`() {
        val out = activityIntensities(emptyList(), emptyList(), NOW_MS, RANGE_MS, BUCKETS)
        assertEquals(BUCKETS, out.size, "size")
        assertTrue(out.all { it == 0f }, "every bucket should be silent")
    }

    @Test
    fun `a zero bucket count returns empty instead of dividing by zero`() {
        assertEquals(0, activityIntensities(emptyList(), emptyList(), NOW_MS, RANGE_MS, 0).size)
    }

    @Test
    fun `a zero range returns empty instead of dividing by zero`() {
        assertEquals(0, activityIntensities(emptyList(), emptyList(), NOW_MS, 0L, BUCKETS).size)
    }

    @Test
    fun `intensities are normalised into 0 to 1 with the peak at exactly 1`() {
        // Motion arrives 0-100 from Frigate; the strip is drawn as a fraction of its own width, so
        // anything outside 0..1 silently draws past the strip edge.
        val motion =
            listOf(
                motionAt(minutesAgo = 10.0, value = 3.0),
                motionAt(minutesAgo = 40.0, value = 97.0),
                motionAt(minutesAgo = 70.0, value = 22.0),
            )
        val out = activityIntensities(motion, emptyList(), NOW_MS, RANGE_MS, BUCKETS)

        assertTrue(out.all { it in 0f..1f }, "out of range: ${out.filterNot { it in 0f..1f }}")
        assertEquals(1f, out.max(), TOLERANCE, "the loudest bucket should reach full width")
    }

    @Test
    fun `bucket zero is the newest slice, not the oldest`() {
        // The orientation bug this test exists for: the rail draws bucket 0 at y=0 (top = now). Fed
        // backwards, the waveform renders mirrored — a plausible-looking picture that puts last
        // night's activity where this morning's should be.
        val out =
            activityIntensities(
                motion = listOf(motionAt(minutesAgo = 6.0, value = 80.0)),
                fallbackSegments = emptyList(),
                viewEndMs = NOW_MS,
                rangeMs = RANGE_MS,
                numBuckets = BUCKETS,
            )
        // 120 min over 60 buckets = 2 min each, so 6 minutes ago lands in bucket 3.
        assertEquals(3, out.indexOfFirst { it == out.max() }, "peak bucket")
    }

    @Test
    fun `activity outside the visible window is ignored`() {
        val out =
            activityIntensities(
                motion = listOf(motionAt(minutesAgo = 300.0, value = 100.0)),
                fallbackSegments = emptyList(),
                viewEndMs = NOW_MS,
                rangeMs = RANGE_MS,
                numBuckets = BUCKETS,
            )
        assertTrue(out.all { it == 0f }, "a point 5h back must not appear in a 2h window")
    }

    @Test
    fun `review items stand in for the waveform when the motion endpoint gives nothing`() {
        // Frigate before 0.14 has no api/review/activity/motion. The strip should still show
        // roughly when things happened rather than going blank.
        val out =
            activityIntensities(
                motion = emptyList(),
                fallbackSegments =
                    listOf(
                        segmentAt(minutesAgo = 8.0),
                        segmentAt(minutesAgo = 8.5),
                        segmentAt(minutesAgo = 90.0),
                    ),
                viewEndMs = NOW_MS,
                rangeMs = RANGE_MS,
                numBuckets = BUCKETS,
            )
        assertTrue(out.any { it > 0f }, "fallback density should not be silent")
        assertEquals(1f, out.max(), TOLERANCE, "fallback should normalise the same way")
        // The two clustered items outweigh the lone one, so the peak sits near the top.
        assertTrue(out.indexOfFirst { it == out.max() } < BUCKETS / 2, "peak should be in the recent half")
    }

    @Test
    fun `a lone spike is smoothed into its neighbours instead of drawing as one hard line`() {
        val out =
            activityIntensities(
                motion = listOf(motionAt(minutesAgo = 60.0, value = 50.0)),
                fallbackSegments = emptyList(),
                viewEndMs = NOW_MS,
                rangeMs = RANGE_MS,
                numBuckets = BUCKETS,
            )
        val peak = out.indexOfFirst { it == out.max() }
        assertTrue(out[peak - 1] > 0f, "bucket before the peak should be lifted")
        assertTrue(out[peak + 1] > 0f, "bucket after the peak should be lifted")
        assertTrue(out[peak - 1] < out[peak], "the peak must still be the peak")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun motionAt(
        minutesAgo: Double,
        value: Double,
    ) = MotionActivity(startTime = secondsAt(minutesAgo), motion = value)

    private fun segmentAt(minutesAgo: Double) =
        ReviewSegment(
            id = "seg-$minutesAgo",
            camera = "ch1",
            severity = "alert",
            startTime = secondsAt(minutesAgo),
        )

    private fun secondsAt(minutesAgo: Double): Double = (NOW_MS - (minutesAgo * 60_000).toLong()) / 1000.0

    private companion object {
        /** Fixed so bucket arithmetic is exact and the tests never depend on the wall clock. */
        const val NOW_MS = 1_783_144_800_000L
        const val RANGE_MS = 2 * 60 * 60 * 1000L
        const val BUCKETS = 60
        const val TOLERANCE = 0.0001f

        val ZOOMS = listOf(0.25f, 0.5f, 1f, 2f, 4f, 8f, 16f, 24f, 36f, 96f, 200f)
    }
}
