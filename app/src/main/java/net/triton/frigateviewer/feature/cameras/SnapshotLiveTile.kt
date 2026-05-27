package net.triton.frigateviewer.feature.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier,
) {
    var refreshTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(baseUrl, cameraName) {
        while (true) {
            delay(800)
            refreshTime = System.currentTimeMillis()
        }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val bboxParam = if (showBoundingBoxes) "&bbox=1" else ""
        val url = "${baseUrl.trimEnd('/')}/api/$cameraName/latest.jpg?h=480&t=$refreshTime$bboxParam"

        // Not keyed on url — holds the last successful frame while the next loads.
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
            onSuccess = { lastPainter = it.painter },
        )

        Text(
            "Snapshot",
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Red.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}
