package net.triton.frigateviewer.feature.cameras

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** How long the indicator lingers after the last pinch or drag before fading out. */
private const val IDLE_FADE_DELAY_MS = 1_500L

private const val FADE_IN_MS = 120
private const val FADE_OUT_MS = 350

/** Width of the minimap. Small enough to stay out of the way, big enough to read at a glance. */
private val INDICATOR_WIDTH = 56.dp

private val INDICATOR_CORNER = 4.dp
private val INDICATOR_BORDER = 1.5.dp
private val INDICATOR_INSET = 2.dp

/**
 * Zoom minimap: an outline of the whole camera frame with the currently visible portion filled
 * inside it.
 *
 * Reads as "how far am I zoomed in, and where am I looking" in one glance — the fill shrinks as the
 * scale grows and slides as the picture is panned. Hidden entirely at 1x, and faded out after
 * [IDLE_FADE_DELAY_MS] of no gesture so it isn't permanent chrome over the picture.
 *
 * Sized to the frame's aspect ratio rather than a fixed box, so the outline is a scale model of the
 * frame and the fill inside it is honest about both axes.
 */
@Composable
fun ZoomIndicator(
    state: ZoomPanState,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }

    // Keyed on interactionCount so every gesture sample restarts the countdown; the delay only
    // elapses once the user has actually stopped touching the tile.
    LaunchedEffect(state.interactionCount, state.isZoomed) {
        if (!state.isZoomed) {
            visible = false
            return@LaunchedEffect
        }
        visible = true
        delay(IDLE_FADE_DELAY_MS)
        visible = false
    }

    val content = state.contentSize
    val aspectRatio =
        if (content.height > 0f && content.width > 0f) content.width / content.height else 16f / 9f
    val region = visibleFrameRegion(state.viewport, content, state.scale, state.offset)

    // Matches the rest of the on-video tile chrome, which is white-on-scrim regardless of theme
    // (see the mute and fullscreen buttons); the fill takes the theme accent.
    val frameColor = Color.White.copy(alpha = 0.85f)
    val scrimColor = Color.Black.copy(alpha = 0.35f)
    val regionColor = MaterialTheme.colorScheme.primary
    val zoomLabel = "Zoomed ${(state.scale * 10).roundToInt() / 10f}x"

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(FADE_IN_MS)),
        exit = fadeOut(animationSpec = tween(FADE_OUT_MS)),
        modifier = modifier,
    ) {
        Canvas(
            modifier =
                Modifier
                    .width(INDICATOR_WIDTH)
                    .height(INDICATOR_WIDTH / aspectRatio)
                    .clip(RoundedCornerShape(INDICATOR_CORNER))
                    .background(scrimColor)
                    .border(
                        width = INDICATOR_BORDER,
                        color = frameColor,
                        shape = RoundedCornerShape(INDICATOR_CORNER),
                    ).padding(INDICATOR_INSET)
                    .testTag("zoom_indicator")
                    .semantics { contentDescription = zoomLabel },
        ) {
            drawVisibleRegion(
                left = region.left,
                top = region.top,
                right = region.right,
                bottom = region.bottom,
                color = regionColor,
            )
        }
    }
}

/**
 * Fills the normalised [left]/[top]/[right]/[bottom] sub-rect of this canvas.
 *
 * Clamped to at least a hairline on each axis so the fill never vanishes entirely at maximum zoom —
 * an empty outline reads as "broken", not as "very zoomed in".
 */
private fun DrawScope.drawVisibleRegion(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    color: Color,
) {
    val minExtent = 2.dp.toPx()
    val width = ((right - left) * size.width).coerceAtLeast(minExtent)
    val height = ((bottom - top) * size.height).coerceAtLeast(minExtent)
    val x = (left * size.width).coerceIn(0f, (size.width - width).coerceAtLeast(0f))
    val y = (top * size.height).coerceIn(0f, (size.height - height).coerceAtLeast(0f))

    drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(width, height),
        cornerRadius = CornerRadius(1.dp.toPx()),
    )
}
