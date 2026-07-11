package net.triton.frigateviewer.feature.events

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Thumbnail bubble shown while actively touching a timeline. [bitmap] is a still frame
 * extracted locally from the currently-cached preview clip (see
 * `core/media/PreviewFrameExtractor.kt`), or null to render nothing (extraction failed, or no
 * clip cached yet for this touch position). Positioning is entirely up to the caller's
 * [modifier].
 */
@Composable
fun PreviewThumbnailBubble(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    bubbleWidth: Dp = 120.dp,
) {
    if (bitmap == null) return
    Box(
        modifier
            .size(width = bubbleWidth, height = bubbleWidth * 9f / 16f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Preview",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = bubbleWidth, height = bubbleWidth * 9f / 16f),
        )
    }
}
