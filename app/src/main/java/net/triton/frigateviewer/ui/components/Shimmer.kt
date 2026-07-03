package net.triton.frigateviewer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import coil3.ImageLoader
import net.triton.frigateviewer.core.image.FrigateImage

@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
            ),
        label = "shimmerOffset",
    )
    val colors =
        listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant,
        )
    Box(
        modifier =
            modifier.background(
                Brush.linearGradient(
                    colors = colors,
                    start = Offset(offset - 500f, 0f),
                    end = Offset(offset + 500f, 0f),
                ),
            ),
    )
}

@Composable
fun CameraSkeletonTile(
    modifier: Modifier = Modifier,
    cameraName: String? = null,
    baseUrl: String? = null,
    imageLoader: ImageLoader? = null,
) {
    val hasCachedBg = cameraName != null && baseUrl != null && imageLoader != null
    Card(modifier = modifier.aspectRatio(16f / 9f)) {
        Box(Modifier.fillMaxSize()) {
            if (hasCachedBg) {
                FrigateImage(
                    relativePath = "api/$cameraName/latest.jpg?h=360&quality=60",
                    contentDescription = null,
                    baseUrl = baseUrl,
                    imageLoader = imageLoader,
                    crossfade = false,
                    diskCache = true,
                    diskCacheKey = "snap_$cameraName",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 50% alpha when an image is underneath so the cached snapshot shows through
            ShimmerBox(
                Modifier
                    .fillMaxSize()
                    .then(if (hasCachedBg) Modifier.alpha(0.5f) else Modifier),
            )
            // No center spinner — the caller overlays a pill + status badge instead
        }
    }
}
