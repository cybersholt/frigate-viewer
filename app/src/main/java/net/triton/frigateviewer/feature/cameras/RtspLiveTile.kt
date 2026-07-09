@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")

package net.triton.frigateviewer.feature.cameras

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import net.triton.frigateviewer.LocalFullScreenMode

private const val TAG = "RtspLiveTile"

private fun maskRtspUrl(url: String): String = url.replace(Regex("(rtsp://[^:@/]+):([^@]+)@"), "$1:***@")

private fun friendlyError(e: PlaybackException): String =
    when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> {
            "Can't reach the RTSP server. Is port accessible from this device?"
        }

        PlaybackException.ERROR_CODE_TIMEOUT -> {
            "Connection timed out."
        }

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            "Server rejected the connection — likely an auth failure."
        }

        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> {
            "Permission denied by server."
        }

        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> {
            "Clear-text RTSP blocked. The stream must use TCP."
        }

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        -> {
            "Unsupported stream format."
        }

        else -> {
            "Error ${e.errorCode}: ${e.message ?: "unknown"}"
        }
    }

@OptIn(UnstableApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RtspLiveTile(
    url: String,
    modifier: Modifier = Modifier,
    snapshotUrl: String? = null,
    snapshotCachedAt: Long = 0L,
    autoLandscapeOnStream: Boolean = false,
    onStateChanged: (CameraStreamState) -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    var retryTrigger by remember { mutableIntStateOf(0) }
    var isFullScreen by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }
    val fullScreenMode = LocalFullScreenMode.current

    val streamState =
        remember(url, retryTrigger) {
            mutableStateOf<CameraStreamState>(
                if (snapshotUrl != null) {
                    CameraStreamState.LoadingWithCache(
                        if (snapshotCachedAt > 0L) snapshotCachedAt else System.currentTimeMillis(),
                    )
                } else {
                    CameraStreamState.Skeleton
                },
            )
        }
    var streamStateVal by streamState

    LaunchedEffect(streamStateVal) { onStateChanged(streamStateVal) }

    val exoPlayer =
        remember(url, retryTrigger) {
            Log.i(TAG, "Connecting → ${maskRtspUrl(url)}")
            // setEnableDecoderFallback: some hardware H.264 decoders (e.g. Exynos, on 4K streams)
            // reject setOutputSurface with BAD_INDEX. Without fallback that's a fatal codec crash;
            // with it, ExoPlayer retries the same stream on a software decoder.
            val renderersFactory =
                DefaultRenderersFactory(context)
                    .setEnableDecoderFallback(true)
            ExoPlayer.Builder(context, renderersFactory).build().apply {
                volume = if (isMuted) 0f else 1f
                addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            val name =
                                when (state) {
                                    Player.STATE_IDLE -> "IDLE"
                                    Player.STATE_BUFFERING -> "BUFFERING"
                                    Player.STATE_READY -> "READY"
                                    Player.STATE_ENDED -> "ENDED"
                                    else -> "UNKNOWN($state)"
                                }
                            Log.d(TAG, "State → $name (url=${maskRtspUrl(url)})")
                        }

                        override fun onRenderedFirstFrame() {
                            streamState.value = CameraStreamState.Live
                        }

                        override fun onPlayerError(e: PlaybackException) {
                            val msg = friendlyError(e)
                            Log.e(TAG, "Error on ${maskRtspUrl(url)}: code=${e.errorCode} — $msg", e)
                            e.cause?.let { cause ->
                                Log.e(TAG, "  caused by: ${cause.javaClass.simpleName}: ${cause.message}")
                            }
                            streamState.value = CameraStreamState.Offline(msg)
                        }
                    },
                )
                val mediaSource =
                    RtspMediaSource
                        .Factory()
                        .setForceUseRtpTcp(true)
                        .createMediaSource(MediaItem.fromUri(url))
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
        }

    LaunchedEffect(Unit) {
        if (autoLandscapeOnStream) isFullScreen = true
    }

    LaunchedEffect(isFullScreen, autoLandscapeOnStream) {
        val activity = context as? Activity ?: return@LaunchedEffect
        activity.requestedOrientation =
            if (isFullScreen && autoLandscapeOnStream) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
    }

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    LaunchedEffect(isFullScreen) { fullScreenMode.value = isFullScreen }
    DisposableEffect(Unit) { onDispose { fullScreenMode.value = false } }

    DisposableEffect(isFullScreen) {
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowInsetsControllerCompat(window, view)
        if (isFullScreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    if (isFullScreen) {
        BackHandler { isFullScreen = false }
    }

    // Reconnect when returning from background: ExoPlayer stalls with a black
    // surface after the app is stopped. Bumping retryTrigger recreates the player.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var stopped = false
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                        stopped = true
                    }

                    androidx.lifecycle.Lifecycle.Event.ON_START -> {
                        if (stopped) {
                            stopped = false
                            retryTrigger++
                        }
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(url, retryTrigger) {
        onDispose {
            Log.d(TAG, "Releasing player (url=${maskRtspUrl(url)}, retry=$retryTrigger)")
            exoPlayer.release()
        }
    }

    Box(
        modifier =
            (if (isFullScreen) Modifier.fillMaxSize() else modifier)
                .background(Color.Black)
                .pointerInput("zoom") {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        zoomOffset = if (scale > 1f) zoomOffset + pan else Offset.Zero
                    }
                }.pointerInput("tap") {
                    detectTapGestures(onDoubleTap = {
                        scale = 1f
                        zoomOffset = Offset.Zero
                    })
                },
        contentAlignment = Alignment.Center,
    ) {
        // PlayerView is ALWAYS in the tree so the SurfaceView's surface exists before
        // STATE_READY fires. Showing it conditionally causes a black screen because
        // ExoPlayer tries to render to a surface that doesn't exist yet.
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = zoomOffset.x
                    translationY = zoomOffset.y
                    clip = true
                },
        )

        // State-based overlay — camera pill and status badge are rendered by the parent
        // StreamContent via StreamTileBadgeLayer so they appear above all stream types.
        when (val state = streamStateVal) {
            CameraStreamState.Skeleton -> {
                Box(
                    Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LoadingIndicator(
                            modifier = Modifier.size(48.dp),
                            color = Color.White.copy(alpha = 0.80f),
                        )
                        Text(
                            "Connecting via RTSP…",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }

            is CameraStreamState.LoadingWithCache -> {
                if (snapshotUrl != null) {
                    AsyncImage(
                        model =
                            ImageRequest
                                .Builder(context)
                                .data(snapshotUrl)
                                .crossfade(false)
                                .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        alpha = 0.8f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (snapshotUrl != null) 0.15f else 0.9f)),
                )
            }

            CameraStreamState.Live -> { /* video frame visible through PlayerView */ }

            is CameraStreamState.Offline -> {
                if (snapshotUrl != null) {
                    AsyncImage(
                        model =
                            ImageRequest
                                .Builder(context)
                                .data(snapshotUrl)
                                .crossfade(false)
                                .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        alpha = 0.5f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.60f)),
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideocamOff,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.54f),
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            "Device offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.70f),
                        )
                        if (state.reason != null) {
                            Text(
                                state.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.50f),
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = { retryTrigger++ },
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("Retry", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }

        // Mute toggle
        IconButton(
            onClick = { isMuted = !isMuted },
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
        ) {
            Icon(
                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }

        // Fullscreen toggle
        IconButton(
            onClick = { isFullScreen = !isFullScreen },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 8.dp),
        ) {
            Icon(
                imageVector = if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (isFullScreen) "Exit full screen" else "Full screen",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
