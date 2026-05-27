package net.triton.frigateviewer.feature.cameras

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import net.triton.frigateviewer.LocalFullScreenMode
import net.triton.frigateviewer.core.data.CredentialStore
import net.triton.frigateviewer.core.image.FrigateImage
import net.triton.frigateviewer.ui.components.CameraPill
import net.triton.frigateviewer.ui.components.CameraSkeletonTile

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CamerasEntryPoint {
    fun imageLoader(): ImageLoader

    fun frigateClient(): net.triton.frigateviewer.core.network.FrigateClient

    fun credentialStore(): CredentialStore
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamerasScreen(vm: CamerasViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val entryPoint =
        remember {
            EntryPointAccessors.fromApplication(context, CamerasEntryPoint::class.java)
        }
    val imageLoader = entryPoint.imageLoader()
    val frigateClient = entryPoint.frigateClient()
    var focused by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading && state.cameras.isEmpty() -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(state.gridColumns),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(6) { CameraSkeletonTile(Modifier.fillMaxWidth()) }
                }
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(64.dp),
                    strokeWidth = 6.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            state.noServerConfigured -> {
                EmptyState(
                    title = "No server configured",
                    body = "Open Settings and add your Frigate server.",
                )
            }

            state.errorMessage != null && state.cameras.isEmpty() -> {
                ErrorState(state.errorMessage!!, onRetry = { vm.refresh() })
            }

            else -> {
                if (focused != null) {
                    BackHandler { focused = null }
                    FocusedTile(
                        cameraName = focused!!,
                        server = state.activeServer,
                        effectiveBaseUrl = state.effectiveBaseUrl,
                        frigateClient = frigateClient,
                        imageLoader = imageLoader,
                        preferSubStream = state.preferSubStream,
                        go2rtcStreams = state.go2rtcStreams,
                        hideEventImage = state.hideEventImage,
                        liveStreamOption = state.liveStreamOption,
                        refreshTimestamp = state.refreshTimestamp,
                        showBoundingBoxes = state.showBoundingBoxes,
                        autoLandscapeOnStream = state.autoLandscapeOnStream,
                        onClose = { focused = null },
                    )
                } else {
                    PullToRefreshBox(
                        isRefreshing = state.loading,
                        onRefresh = { vm.refresh(forceCacheRefresh = true) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(state.gridColumns),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.cameras.entries.toList(), key = { it.key }) { (name, _) ->
                                CameraTile(
                                    name = name,
                                    baseUrl = state.effectiveBaseUrl,
                                    imageLoader = imageLoader,
                                    refreshTimestamp = state.refreshTimestamp,
                                    showBoundingBoxes = state.showBoundingBoxes,
                                    onClick = { focused = name },
                                )
                            }
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
    refreshTimestamp: Long,
    showBoundingBoxes: Boolean,
    onClick: () -> Unit,
) {
    Card(Modifier.aspectRatio(16f / 9f).clickable { onClick() }) {
        Box(Modifier.fillMaxSize()) {
            val bboxParam = if (showBoundingBoxes) "&bbox=1" else ""
            FrigateImage(
                relativePath = "api/$name/latest.jpg?h=360&quality=60&t=$refreshTimestamp$bboxParam",
                contentDescription = name,
                baseUrl = baseUrl,
                imageLoader = imageLoader,
                crossfade = false,
                diskCache = false,
                modifier = Modifier.fillMaxSize(),
            )
            CameraPill(
                camera = name,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
            )
        }
    }
}

@Composable
private fun FocusedTile(
    cameraName: String,
    server: net.triton.frigateviewer.core.data.Server?,
    effectiveBaseUrl: String?,
    frigateClient: net.triton.frigateviewer.core.network.FrigateClient,
    imageLoader: ImageLoader,
    preferSubStream: Boolean,
    go2rtcStreams: Set<String>,
    hideEventImage: Boolean,
    liveStreamOption: String,
    refreshTimestamp: Long,
    showBoundingBoxes: Boolean,
    autoLandscapeOnStream: Boolean,
    onClose: () -> Unit,
) {
    val baseUrl = effectiveBaseUrl ?: server?.baseUrl()
    val context = androidx.compose.ui.platform.LocalContext.current
    val entryPoint =
        remember {
            EntryPointAccessors.fromApplication(context, CamerasEntryPoint::class.java)
        }
    val credentialStore = remember { entryPoint.credentialStore() }

    val okHttpClient by androidx.compose.runtime.produceState<okhttp3.OkHttpClient?>(initialValue = null, server) {
        value = if (server != null) frigateClient.clientFor(server) else null
    }

    val subStreamName = "${cameraName}_sub"
    val liveCameraName = if (preferSubStream && go2rtcStreams.contains(subStreamName)) subStreamName else cameraName
    val bboxParam = if (showBoundingBoxes) "&bbox=1" else ""
    val snapshotPath = "api/$cameraName/latest.jpg?h=720&t=$refreshTimestamp$bboxParam"

    val rtspUrl by androidx.compose.runtime.produceState<String?>(
        initialValue = null,
        server,
        liveCameraName,
    ) {
        if (server == null) {
            value = null
            return@produceState
        }
        val rtspTargetHost = server.rtspHost?.takeIf { it.isNotBlank() } ?: server.host
        val base = "rtsp://$rtspTargetHost:${server.rtspPort}/$liveCameraName"
        val secret = credentialStore.rawSecret(server.id)
        value =
            if (secret != null && !secret.startsWith(CredentialStore.BEARER_PREFIX)) {
                val parts = secret.split(":", limit = 2)
                if (parts.size == 2) {
                    val u = java.net.URLEncoder.encode(parts[0], "UTF-8")
                    val p = java.net.URLEncoder.encode(parts[1], "UTF-8")
                    "rtsp://$u:$p@${server.host}:${server.rtspPort}/$liveCameraName"
                } else {
                    base
                }
            } else {
                base
            }
    }

    val isFullScreen = LocalFullScreenMode.current.value
    var hasBeenFullScreen by remember { mutableStateOf(false) }
    LaunchedEffect(isFullScreen) {
        if (isFullScreen) {
            hasBeenFullScreen = true
        } else if (hasBeenFullScreen && autoLandscapeOnStream) {
            onClose()
        }
    }

    // StreamContent must stay at ONE stable tree position so the tile is never
    // disposed/recreated when isFullScreen toggles. Branching the outer layout
    // would move StreamContent between two source positions → tile restarts →
    // LaunchedEffect(Unit) fires again → auto-fullscreen re-triggers → back
    // gesture trapped forever. Keep the Column structure constant; only
    // show/hide the header and footer around the fixed stream slot.
    Column(Modifier.fillMaxSize().padding(if (isFullScreen) 0.dp else 8.dp)) {
        if (!isFullScreen) {
            Box(Modifier.fillMaxWidth().clickable { onClose() }.padding(bottom = 8.dp)) {
                Text("← $cameraName (tap to close)", style = MaterialTheme.typography.titleMedium)
            }
        }
        Box(if (isFullScreen) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            StreamContent(
                liveStreamOption = liveStreamOption,
                server = server,
                okHttpClient = okHttpClient,
                rtspUrl = rtspUrl,
                baseUrl = baseUrl,
                snapshotPath = snapshotPath,
                liveCameraName = liveCameraName,
                cameraName = cameraName,
                imageLoader = imageLoader,
                showBoundingBoxes = showBoundingBoxes,
                autoLandscapeOnStream = autoLandscapeOnStream,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!isFullScreen && !hideEventImage) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                FrigateImage(
                    relativePath = snapshotPath,
                    contentDescription = "$cameraName last snapshot",
                    baseUrl = baseUrl,
                    imageLoader = imageLoader,
                    crossfade = false,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
        }
    }
}

@Composable
private fun StreamContent(
    liveStreamOption: String,
    server: net.triton.frigateviewer.core.data.Server?,
    okHttpClient: okhttp3.OkHttpClient?,
    rtspUrl: String?,
    baseUrl: String?,
    snapshotPath: String,
    liveCameraName: String,
    cameraName: String,
    imageLoader: ImageLoader,
    showBoundingBoxes: Boolean,
    autoLandscapeOnStream: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (server != null && okHttpClient != null) {
            when (liveStreamOption) {
                "rtsp" -> {
                    val resolvedRtspUrl = rtspUrl
                    if (resolvedRtspUrl != null) {
                        RtspLiveTile(
                            url = resolvedRtspUrl,
                            snapshotUrl = baseUrl?.trimEnd('/') + "/" + snapshotPath,
                            autoLandscapeOnStream = autoLandscapeOnStream,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize().background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(color = Color.White) }
                    }
                }

                "snapshot" -> {
                    SnapshotLiveTile(
                        baseUrl = baseUrl ?: "",
                        cameraName = cameraName,
                        imageLoader = imageLoader,
                        showBoundingBoxes = showBoundingBoxes,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    WebRtcLiveTile(
                        baseUrl = baseUrl ?: "",
                        cameraName = liveCameraName,
                        okHttpClient = okHttpClient,
                        snapshotUrl = baseUrl?.trimEnd('/') + "/" + snapshotPath,
                        autoLandscapeOnStream = autoLandscapeOnStream,
                        showBoundingBoxes = showBoundingBoxes,
                        onFatal = { },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    body: String,
) {
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
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
) {
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
