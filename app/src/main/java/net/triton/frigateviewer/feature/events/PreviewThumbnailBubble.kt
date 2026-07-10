package net.triton.frigateviewer.feature.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import net.triton.frigateviewer.core.image.FrigateImage

/**
 * Floating YouTube-style thumbnail bubble shown while actively touching a timeline, following
 * the finger. [offsetY] is the bubble's top-left Y within its parent Box (already clamped by
 * the caller so it can't run off the top/bottom); [fileName] is the nearest cached preview
 * frame to the touched time, or null to render nothing (outside the cache's retention window).
 */
@Composable
fun PreviewThumbnailBubble(
    fileName: String?,
    baseUrl: String?,
    imageLoader: ImageLoader,
    offsetY: Dp,
    modifier: Modifier = Modifier,
    bubbleWidth: Dp = 120.dp,
) {
    if (fileName == null || baseUrl == null) return
    Box(
        modifier
            .offset(y = offsetY)
            .size(width = bubbleWidth, height = bubbleWidth * 9f / 16f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        FrigateImage(
            relativePath = "api/preview/$fileName/thumbnail.webp",
            contentDescription = "Preview",
            baseUrl = baseUrl,
            imageLoader = imageLoader,
            crossfade = false,
            diskCache = false,
            modifier = Modifier.size(width = bubbleWidth, height = bubbleWidth * 9f / 16f),
        )
    }
}
