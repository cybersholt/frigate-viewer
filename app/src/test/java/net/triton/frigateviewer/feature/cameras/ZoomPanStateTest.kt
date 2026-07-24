package net.triton.frigateviewer.feature.cameras

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The clamp math behind "keep the video contained to the viewport".
 *
 * These are the pure functions extracted out of the two live tiles precisely so this invariant is
 * checkable without a device: the bug being fixed here (video draggable almost entirely off-screen)
 * was invisible to code review because the offending code — `zoomOffset = zoomOffset + pan` — looks
 * perfectly reasonable until you notice nothing bounds it.
 */
class ZoomPanStateTest {
    private val viewport = Size(1000f, 500f)

    /** 16:9 inside a 2:1 viewport is height-limited, so it pillarboxes left/right. */
    private val wideContent = fitContentSize(viewport, 16f / 9f)

    private fun assertOffsetEquals(
        expected: Offset,
        actual: Offset,
    ) {
        assertEquals(expected.x, actual.x, TOLERANCE, "x")
        assertEquals(expected.y, actual.y, TOLERANCE, "y")
    }

    @Test
    fun `fit content height-limits a 16 by 9 frame inside a 2 by 1 viewport`() {
        // Width-limited would be 1000x562.5 — taller than the viewport — so height wins.
        val fitted = fitContentSize(Size(1000f, 500f), 16f / 9f)
        assertEquals(500f * 16f / 9f, fitted.width, TOLERANCE)
        assertEquals(500f, fitted.height, TOLERANCE)
    }

    @Test
    fun `fit content pillarboxes a square frame inside a wider viewport`() {
        val fitted = fitContentSize(Size(1000f, 500f), 1f)
        assertEquals(500f, fitted.width, TOLERANCE)
        assertEquals(500f, fitted.height, TOLERANCE)
    }

    @Test
    fun `no pan is allowed at 1x`() {
        assertOffsetEquals(Offset.Zero, maxPanOffset(viewport, viewport, MIN_ZOOM))
    }

    @Test
    fun `pan limit is half the overhang beyond the viewport`() {
        // 1000-wide content at 2x is 2000 wide: 1000 px of overhang, 500 on each side.
        val limit = maxPanOffset(viewport, viewport, 2f)
        assertEquals(500f, limit.x, TOLERANCE)
        assertEquals(250f, limit.y, TOLERANCE)
    }

    @Test
    fun `an axis that still underfills the viewport cannot be panned`() {
        // A square picture in a 2:1 viewport is 500 wide. At 1.5x it is 750 — still narrower than
        // the 1000 px viewport — so it stays centred horizontally while moving vertically.
        val limit = maxPanOffset(viewport, fitContentSize(viewport, 1f), 1.5f)
        assertEquals(0f, limit.x, TOLERANCE)
        assertEquals(125f, limit.y, TOLERANCE)
    }

    @Test
    fun `a huge pan is clamped to the edge instead of pushing the picture off-screen`() {
        // The reported bug, as a test: drag 5000 px across a viewport 1000 px wide.
        assertOffsetEquals(
            Offset(500f, 250f),
            clampPanOffset(Offset(5000f, 5000f), viewport, viewport, 2f),
        )
    }

    @Test
    fun `clamping is symmetric in the negative direction`() {
        assertOffsetEquals(
            Offset(-500f, -250f),
            clampPanOffset(Offset(-5000f, -5000f), viewport, viewport, 2f),
        )
    }

    @Test
    fun `the picture always covers the viewport at every zoom level and drag distance`() {
        // The invariant the whole change exists to guarantee: after clamping, the scaled picture's
        // edges are never inside the viewport's edges on an axis where it is big enough to cover.
        val scales = listOf(1f, 1.3f, 2f, 3.7f, MAX_ZOOM)
        val drags = listOf(-9000f, -640f, 0f, 640f, 9000f)

        for (scale in scales) {
            for (dx in drags) {
                for (dy in drags) {
                    val clamped = clampPanOffset(Offset(dx, dy), viewport, wideContent, scale)
                    val halfWidth = wideContent.width * scale / 2f
                    val halfHeight = wideContent.height * scale / 2f
                    val case = "scale=$scale dx=$dx dy=$dy"

                    if (halfWidth >= viewport.width / 2f) {
                        assertTrue(
                            clamped.x - halfWidth <= -viewport.width / 2f + TOLERANCE,
                            "left edge leaked inside the viewport at $case",
                        )
                        assertTrue(
                            clamped.x + halfWidth >= viewport.width / 2f - TOLERANCE,
                            "right edge leaked inside the viewport at $case",
                        )
                    }
                    if (halfHeight >= viewport.height / 2f) {
                        assertTrue(
                            clamped.y - halfHeight <= -viewport.height / 2f + TOLERANCE,
                            "top edge leaked inside the viewport at $case",
                        )
                        assertTrue(
                            clamped.y + halfHeight >= viewport.height / 2f - TOLERANCE,
                            "bottom edge leaked inside the viewport at $case",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `pinching out below 1x snaps back to centred`() {
        val (scale, offset) =
            applyTransform(
                scale = 1.2f,
                offset = Offset(80f, 40f),
                centroid = Offset(100f, 100f),
                pan = Offset.Zero,
                zoom = 0.5f,
                viewport = viewport,
                content = viewport,
            )
        assertEquals(MIN_ZOOM, scale, TOLERANCE)
        assertOffsetEquals(Offset.Zero, offset)
    }

    @Test
    fun `zoom is capped at MAX_ZOOM`() {
        val (scale, _) =
            applyTransform(
                scale = 4f,
                offset = Offset.Zero,
                centroid = Offset(500f, 250f),
                pan = Offset.Zero,
                zoom = 10f,
                viewport = viewport,
                content = viewport,
            )
        assertEquals(MAX_ZOOM, scale, TOLERANCE)
    }

    @Test
    fun `pinching at the centre keeps the picture centred`() {
        val (scale, offset) =
            applyTransform(
                scale = MIN_ZOOM,
                offset = Offset.Zero,
                centroid = Offset(500f, 250f), // dead centre of the viewport
                pan = Offset.Zero,
                zoom = 2f,
                viewport = viewport,
                content = viewport,
            )
        assertEquals(2f, scale, TOLERANCE)
        assertOffsetEquals(Offset.Zero, offset)
    }

    @Test
    fun `pinching off-centre anchors to the fingers instead of the middle`() {
        // Pinch 400 px left of centre. Anchoring means the picture shifts right to keep what was
        // under the fingers under the fingers. The old centre-anchored code produced 0 here.
        val (_, offset) =
            applyTransform(
                scale = MIN_ZOOM,
                offset = Offset.Zero,
                centroid = Offset(100f, 250f),
                pan = Offset.Zero,
                zoom = 2f,
                viewport = viewport,
                content = viewport,
            )
        // focus = -400; offset' = -400 - (-400 - 0) * 2 = 400, inside the ±500 limit at 2x.
        assertEquals(400f, offset.x, TOLERANCE)
        assertEquals(0f, offset.y, TOLERANCE)
    }

    @Test
    fun `the anchored point stays put across a zoom step`() {
        val startScale = 1.5f
        val startOffset = Offset(60f, -20f)
        val centroid = Offset(300f, 150f)
        val focus = centroid - Offset(viewport.width / 2f, viewport.height / 2f)

        // The frame point currently rendered under the centroid.
        val framePoint = (focus - startOffset) / startScale

        val (scale, offset) =
            applyTransform(
                scale = startScale,
                offset = startOffset,
                centroid = centroid,
                pan = Offset.Zero,
                zoom = 1.6f,
                viewport = viewport,
                content = viewport,
            )

        // Where that same frame point renders afterwards — it must not have moved.
        assertOffsetEquals(focus, framePoint * scale + offset)
    }

    @Test
    fun `the visible region is the whole frame at 1x`() {
        val region = visibleFrameRegion(viewport, viewport, MIN_ZOOM, Offset.Zero)
        assertEquals(0f, region.left, TOLERANCE)
        assertEquals(0f, region.top, TOLERANCE)
        assertEquals(1f, region.right, TOLERANCE)
        assertEquals(1f, region.bottom, TOLERANCE)
    }

    @Test
    fun `the visible region shrinks as the zoom grows`() {
        val at2x = visibleFrameRegion(viewport, viewport, 2f, Offset.Zero)
        val at4x = visibleFrameRegion(viewport, viewport, 4f, Offset.Zero)
        assertEquals(0.5f, at2x.width, TOLERANCE)
        assertEquals(0.25f, at4x.width, TOLERANCE)
        assertTrue(at4x.width < at2x.width, "4x must show less of the frame than 2x")
    }

    @Test
    fun `panning right moves the visible region toward the left of the frame`() {
        val centred = visibleFrameRegion(viewport, viewport, 2f, Offset.Zero)
        val panned = visibleFrameRegion(viewport, viewport, 2f, Offset(500f, 0f))
        assertTrue(panned.left < centred.left, "panned region should sit left of the centred one")
        assertEquals(0f, panned.left, TOLERANCE) // fully panned = flush against the frame edge
    }

    @Test
    fun `the visible region never escapes the frame`() {
        val region = visibleFrameRegion(viewport, viewport, 3f, Offset(99_999f, -99_999f))
        assertTrue(region.left >= -TOLERANCE, "left in range")
        assertTrue(region.top >= -TOLERANCE, "top in range")
        assertTrue(region.right <= 1f + TOLERANCE, "right in range")
        assertTrue(region.bottom <= 1f + TOLERANCE, "bottom in range")
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
