package net.triton.frigateviewer.core.image

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Composable for Frigate-hosted images (snapshots, thumbnails, latest.jpg).
 *
 * Builds a request against the active server's base URL. The auth-aware
 * ImageLoader is injected via [LocalImageLoader].
 */
@Composable
fun FrigateImage(
    relativePath: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    baseUrl: String?,
    imageLoader: ImageLoader,
) {
    if (baseUrl.isNullOrBlank()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No server", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val url = baseUrl.trimEnd('/') + "/" + relativePath.trimStart('/')
    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(url)
            .crossfade(true)
            .build(),
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
