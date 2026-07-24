package net.triton.frigateviewer.feature.cameras

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max

/** Pinch below this and the tile is treated as un-zoomed: centred, un-pannable, no indicator. */
const val MIN_ZOOM = 1f

/** Beyond ~5x a substream is mostly interpolation; matches what the tiles allowed before. */
const val MAX_ZOOM = 5f

/** Float slop for "is this still exactly 1x?" — pinch deltas never land on a clean 1.0. */
private const val ZOOM_EPSILON = 0.001f

/** Used until the decoder reports the real frame size. */
private const val DEFAULT_ASPECT_RATIO = 16f / 9f

/**
 * The rectangle a video of [aspectRatio] occupies inside [viewport] under fit-within (letterbox)
 * sizing — i.e. the *picture*, excluding the black bars.
 *
 * Both live tiles letterbox, but at different layers: WebRtcLiveTile sizes the renderer itself to
 * this rect at the Compose layout level, while RtspLiveTile hands PlayerView the whole viewport and
 * lets RESIZE_MODE_FIT letterbox internally. Clamping has to be against the picture in both cases,
 * otherwise the RTSP tile would let the video be dragged out by the width of its own bars.
 */
fun fitContentSize(
    viewport: Size,
    aspectRatio: Float,
): Size {
    if (viewport.width <= 0f || viewport.height <= 0f || aspectRatio <= 0f) return viewport
    val heightFromWidth = viewport.width / aspectRatio
    return if (heightFromWidth <= viewport.height) {
        Size(viewport.width, heightFromWidth)
    } else {
        Size(viewport.height * aspectRatio, viewport.height)
    }
}

/**
 * Largest translation, in pixels, that keeps [content] scaled by [scale] covering [viewport].
 *
 * Zero on an axis where the scaled picture is still narrower/shorter than the viewport: that axis
 * stays centred rather than being draggable into the void. This is the fix for the reported bug —
 * the tiles previously accumulated raw pan deltas with no bound at all, so the picture could be
 * dragged almost entirely off-screen.
 */
fun maxPanOffset(
    viewport: Size,
    content: Size,
    scale: Float,
): Offset =
    Offset(
        x = max(0f, (content.width * scale - viewport.width) / 2f),
        y = max(0f, (content.height * scale - viewport.height) / 2f),
    )

/** [offset] confined to [maxPanOffset]. */
fun clampPanOffset(
    offset: Offset,
    viewport: Size,
    content: Size,
    scale: Float,
): Offset {
    val limit = maxPanOffset(viewport, content, scale)
    return Offset(
        x = offset.x.coerceIn(-limit.x, limit.x),
        y = offset.y.coerceIn(-limit.y, limit.y),
    )
}

/**
 * The portion of the frame currently on screen, in normalised frame coordinates (0..1 on both axes).
 *
 * This is what the minimap draws: a full-frame outline with this rect filled inside it. It shrinks
 * as [scale] grows and slides as [offset] changes, which is the "indicator that scales with the zoom
 * level" behaviour.
 */
fun visibleFrameRegion(
    viewport: Size,
    content: Size,
    scale: Float,
    offset: Offset,
): Rect {
    val scaledWidth = content.width * scale
    val scaledHeight = content.height * scale
    if (scaledWidth <= 0f || scaledHeight <= 0f) return Rect(0f, 0f, 1f, 1f)

    val visibleWidth = (viewport.width / scaledWidth).coerceIn(0f, 1f)
    val visibleHeight = (viewport.height / scaledHeight).coerceIn(0f, 1f)

    // A positive offset drags the picture right, which reveals frame content further *left*.
    val centreX = 0.5f - offset.x / scaledWidth
    val centreY = 0.5f - offset.y / scaledHeight

    val left = (centreX - visibleWidth / 2f).coerceIn(0f, 1f - visibleWidth)
    val top = (centreY - visibleHeight / 2f).coerceIn(0f, 1f - visibleHeight)
    return Rect(left, top, left + visibleWidth, top + visibleHeight)
}

/**
 * Applies one detectTransformGestures sample, keeping the frame point under [centroid] pinned while
 * the scale changes.
 *
 * [centroid] is in viewport-local coordinates (what the gesture detector reports). The tiles apply
 * the result through graphicsLayer, which scales about the layer's centre and *then* translates by
 * an unscaled pixel amount — so working relative to the viewport centre matches the render exactly.
 */
fun applyTransform(
    scale: Float,
    offset: Offset,
    centroid: Offset,
    pan: Offset,
    zoom: Float,
    viewport: Size,
    content: Size,
): Pair<Float, Offset> {
    val nextScale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
    if (nextScale <= MIN_ZOOM + ZOOM_EPSILON) return MIN_ZOOM to Offset.Zero

    val focus = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
    // Solve for the translation that leaves the frame point under `focus` where it already is:
    // screen = frame * scale + offset, so offset' = focus - (focus - offset) * (scale' / scale).
    val ratio = if (scale <= 0f) 1f else nextScale / scale
    val anchored = focus - (focus - offset) * ratio

    return nextScale to clampPanOffset(anchored + pan, viewport, content, nextScale)
}

/**
 * Zoom/pan for a single live tile. Created with [rememberZoomPanState].
 *
 * Holds the viewport and the decoded frame's aspect ratio because both are needed to know where the
 * picture's edges actually are, and both can change mid-session — rotation resizes the viewport, a
 * substream/mainstream switch changes the aspect ratio. Either one re-clamps immediately, so a
 * change can't strand the picture outside its own bounds.
 */
@Stable
class ZoomPanState {
    var scale by mutableFloatStateOf(MIN_ZOOM)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    /** Bumped on every gesture sample; the indicator watches it to restart its idle-fade timer. */
    var interactionCount by mutableIntStateOf(0)
        private set

    private var viewportSize by mutableStateOf(Size.Zero)
    private var aspectRatio by mutableFloatStateOf(DEFAULT_ASPECT_RATIO)

    val viewport: Size get() = viewportSize

    /** The letterboxed picture rect inside the viewport — see [fitContentSize]. */
    val contentSize: Size get() = fitContentSize(viewportSize, aspectRatio)

    val isZoomed: Boolean get() = scale > MIN_ZOOM + ZOOM_EPSILON

    fun onViewportChanged(size: Size) {
        if (size == viewportSize) return
        viewportSize = size
        reclamp()
    }

    fun onAspectRatioChanged(ratio: Float) {
        if (ratio <= 0f || ratio == aspectRatio) return
        aspectRatio = ratio
        reclamp()
    }

    fun onTransform(
        centroid: Offset,
        pan: Offset,
        zoom: Float,
    ) {
        val (nextScale, nextOffset) =
            applyTransform(scale, offset, centroid, pan, zoom, viewportSize, contentSize)
        scale = nextScale
        offset = nextOffset
        interactionCount++
    }

    /** Double-tap: back to the whole frame, centred. */
    fun reset() {
        scale = MIN_ZOOM
        offset = Offset.Zero
        interactionCount++
    }

    private fun reclamp() {
        offset = clampPanOffset(offset, viewportSize, contentSize, scale)
    }
}

@Composable
fun rememberZoomPanState(): ZoomPanState = remember { ZoomPanState() }
