package net.triton.frigateviewer.feature.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import net.triton.frigateviewer.core.image.FrigateImage

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CamerasEntryPoint {
    fun imageLoader(): ImageLoader
}

@Composable
fun CamerasScreen(vm: CamerasViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val imageLoader = remember {
        EntryPointAccessors.fromApplication(context, CamerasEntryPoint::class.java).imageLoader()
    }
    var focused by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().padding(8.dp)) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.noServerConfigured -> EmptyState(
                title = "No server configured",
                body = "Open Settings and add your Frigate server."
            )
            state.errorMessage != null -> ErrorState(state.errorMessage!!, onRetry = vm::refresh)
            state.cameras.isEmpty() -> EmptyState(
                title = "No cameras",
                body = "Your Frigate config has no cameras defined."
            )
            else -> {
                if (focused != null) {
                    FocusedTile(
                        cameraName = focused!!,
                        baseUrl = state.activeServer?.baseUrl(),
                        imageLoader = imageLoader,
                        onClose = { focused = null },
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(180.dp),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.cameras.entries.toList(), key = { it.key }) { (name, _) ->
                            CameraTile(
                                name = name,
                                baseUrl = state.activeServer?.baseUrl(),
                                imageLoader = imageLoader,
                                onClick = { focused = name },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraTile(
    name: String,
    baseUrl: String?,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    Card(Modifier.aspectRatio(16f / 9f).clickable { onClick() }) {
        Box(Modifier.fillMaxSize()) {
            FrigateImage(
                relativePath = "api/$name/latest.jpg?h=360&quality=60",
                contentDescription = name,
                baseUrl = baseUrl,
                imageLoader = imageLoader,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxWidth().background(Color(0x66000000)).padding(4.dp)) {
                Text(name, color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun FocusedTile(
    cameraName: String,
    baseUrl: String?,
    imageLoader: ImageLoader,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().clickable { onClose() }.padding(8.dp)) {
            Text("← $cameraName (tap to close)", style = MaterialTheme.typography.titleMedium)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (baseUrl != null) {
                // Primary: WebRTC sub-second. Falls back to HLS if WebRTC fails.
                WebRtcLiveTile(
                    baseUrl = baseUrl,
                    cameraName = cameraName,
                    onFatal = { /* fallback handled by HLS path below */ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            FrigateImage(
                relativePath = "api/$cameraName/latest.jpg?h=720",
                contentDescription = "$cameraName snapshot",
                baseUrl = baseUrl,
                imageLoader = imageLoader,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Couldn't load cameras", style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
    }
}
