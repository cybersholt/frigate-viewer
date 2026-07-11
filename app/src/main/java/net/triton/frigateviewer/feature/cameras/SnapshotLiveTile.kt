package net.triton.frigateviewer.feature.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay

@Composable
fun SnapshotLiveTile(
    baseUrl: String,
    cameraName: String,
    imageLoader: ImageLoader,
    showBoundingBoxes: Boolean = true,
    snapshotUrl: String? = null,
    modifier: Modifier = Modifier,
    onStateChanged: (LiveStreamState) -> Unit = {},
) {
    var refreshTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var firstFrameLoaded by remember { mutableStateOf(false) }
    var retryTrigger by remember { mutableIntStateOf(0) }
    var liveState by remember { mutableStateOf<LiveStreamState>(LiveStreamState.Idle) }

    LaunchedEffect(liveState) { onStateChanged(liveState) }

    // Snapshot mode has no persistent connection to tear down — polling continues regardless
    // of success/failure. Retry just resets the displayed state and lets the next poll resolve.
    LaunchedEffect(retryTrigger) {
        liveState = LiveStreamState.Connecting(0)
        var elapsed = 0L
        while (!firstFrameLoaded) {
            delay(500)
            if (firstFrameLoaded) break
            elapsed += 500
            if (elapsed >= 20_000) {
                liveState = LiveStreamState.Error(StreamError.SOURCE_UNAVAILABLE)
                return@LaunchedEffect
            }
            liveState = LiveStreamState.Connecting(elapsed)
        }
    }

    LaunchedEffect(baseUrl, cameraName) {
        while (true) {
            delay(800)
            refreshTime = System.currentTimeMillis()
        }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val bboxParam = if (showBoundingBoxes) "&bbox=1" else ""
        val url = "${baseUrl.trimEnd('/')}/api/$cameraName/latest.jpg?h=480&t=$refreshTime$bboxParam"

        // Not keyed on url — holds the last successful frame while the next loads. Always on,
        // unlike RTSP/WebRTC's `showLastImageWhileLoading` setting — this is what makes snapshot
        // polling no-blink (see project history), not an optional poster; Snapshot mode doesn't
        // take that setting at all.
        var lastPainter by remember { mutableStateOf<Painter?>(null) }

        AsyncImage(
            model =
                ImageRequest
                    .Builder(LocalContext.current)
                    .data(url)
                    .crossfade(false)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build(),
            imageLoader = imageLoader,
            contentDescription = "Live snapshot",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            placeholder = lastPainter,
            onSuccess = {
                lastPainter = it.painter
                if (!firstFrameLoaded) {
                    firstFrameLoaded = true
                    liveState = LiveStreamState.Playing
                }
            },
        )

        StreamOverlay(
            state = liveState,
            posterUrl = snapshotUrl,
            showPoster = false, // The AsyncImage above is our surface
            videoRevealed = firstFrameLoaded,
            onRetry = { retryTrigger++ },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
