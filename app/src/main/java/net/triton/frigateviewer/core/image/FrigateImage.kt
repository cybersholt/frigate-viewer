package net.triton.frigateviewer.core.image

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Frigate-hosted image. Holds the previous frame while loading the next one so
 * auto-refresh never causes a blank flash between frames.
 *
 * @param diskCache Set false for rapidly-changing URLs so they don't generate junk
 *   entries in the 256 MB disk cache. Defaults true so event images are cached.
 * @param diskWriteOnly When true, always fetches from network but writes result to
 *   disk under [diskCacheKey]. Use for live snapshot tiles: always fresh, but persists
 *   the latest frame so skeleton tiles can read it back on next cold start.
 * @param diskCacheKey Stable key for the disk cache entry. Overrides the URL-based
 *   key. Lets callers share a cache entry across URLs that differ only by timestamp.
 */
@Composable
fun FrigateImage(
    relativePath: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    baseUrl: String?,
    imageLoader: ImageLoader,
    crossfade: Boolean = true,
    diskCache: Boolean = true,
    diskWriteOnly: Boolean = false,
    diskCacheKey: String? = null,
) {
    if (baseUrl.isNullOrBlank()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No server", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val url = baseUrl.trimEnd('/') + "/" + relativePath.trimStart('/')

    // Not keyed on url — persists across timestamp changes so the last successful
    // frame is shown as placeholder while the next loads (no blink on refresh).
    var lastPainter by remember { mutableStateOf<Painter?>(null) }

    val diskPolicy =
        when {
            diskWriteOnly -> CachePolicy.WRITE_ONLY
            diskCache -> CachePolicy.ENABLED
            else -> CachePolicy.DISABLED
        }
    AsyncImage(
        model =
            ImageRequest
                .Builder(LocalPlatformContext.current)
                .data(url)
                .crossfade(crossfade)
                .diskCachePolicy(diskPolicy)
                .apply { if (diskCacheKey != null) diskCacheKey(diskCacheKey) }
                .build(),
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        placeholder = lastPainter,
        onSuccess = { lastPainter = it.painter },
    )
}
