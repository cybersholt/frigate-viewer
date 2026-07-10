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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.triton.frigateviewer.LocalFullScreenMode

private const val TAG = "RtspLiveTile"
private const val MAX_BACKOFF_MS = 30_000L

/** Exponential backoff (base, base×2, base×4, ...) capped at [MAX_BACKOFF_MS]. */
private fun backoffMs(
    attempt: Int,
    baseDelaySeconds: Int,
): Long = (baseDelaySeconds * 1000L * (1L shl (attempt - 1))).coerceAtMost(MAX_BACKOFF_MS)

private fun maskRtspUrl(url: String): String = url.replace(Regex("(rtsp://[^:@/]+):([^@]+)@"), "$1:***@")

private fun classifyError(e: PlaybackException): StreamError =
    when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> StreamError.NETWORK

        PlaybackException.ERROR_CODE_TIMEOUT -> StreamError.TIMEOUT

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        -> StreamError.SOURCE_UNAVAILABLE

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        -> StreamError.DECODE_FAILED

        else -> StreamError.NETWORK
    }

@OptIn(UnstableApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RtspLiveTile(
    url: String,
    modifier: Modifier = Modifier,
    snapshotUrl: String? = null,
    snapshotCachedAt: Long = 0L,
    autoLandscapeOnStream: Boolean = false,
    showLastImageWhileLoading: Boolean = true,
    onStateChanged: (LiveStreamState) -> Unit = {},
    onFatal: () -> Unit = {},
) {
    val reconnectSettings = LocalRtspReconnectSettings.current
    val maxReconnectAttempts = reconnectSettings.maxAttempts
    val reconnectBaseDelaySeconds = reconnectSettings.baseDelaySeconds
    val context = LocalContext.current
    val view = LocalView.current
    var retryTrigger by remember { mutableIntStateOf(0) }
    var autoReconnectAttempts by remember { mutableIntStateOf(0) }
    var isFullScreen by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }
    var videoRevealed by remember { mutableStateOf(false) }
    val fullScreenMode = LocalFullScreenMode.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var liveState by remember { mutableStateOf<LiveStreamState>(LiveStreamState.Idle) }
    LaunchedEffect(liveState) { onStateChanged(liveState) }

    // NETWORK and TIMEOUT are both treated as transient (a stalled/dropped connection commonly
    // surfaces as either depending on exactly when the socket gives up) — SOURCE_UNAVAILABLE
    // (bad HTTP status / no permission / cleartext blocked) and DECODE_FAILED are genuine
    // config/format problems that retrying won't fix.
    fun handleFailure(reason: StreamError) {
        val isTransient = reason == StreamError.NETWORK || reason == StreamError.TIMEOUT
        if (isTransient && autoReconnectAttempts < maxReconnectAttempts) {
            autoReconnectAttempts++
            liveState = LiveStreamState.Reconnecting(autoReconnectAttempts)
            val delayMs = backoffMs(autoReconnectAttempts, reconnectBaseDelaySeconds)
            scope.launch {
                delay(delayMs)
                retryTrigger++
            }
        } else {
            liveState = LiveStreamState.Error(reason)
            onFatal()
        }
    }

    // Timeout watchdog: server can take >10s (measured 17.4s against a real unhealthy camera),
    // so give it 20s total before declaring a hard timeout rather than trusting ExoPlayer alone.
    LaunchedEffect(retryTrigger) {
        videoRevealed = false
        liveState = LiveStreamState.Connecting(0)
        var elapsed = 0L
        while (true) {
            delay(500)
            elapsed += 500
            val s = liveState
            if (s !is LiveStreamState.Connecting && s !is LiveStreamState.Negotiating && s !is LiveStreamState.Buffering) {
                break
            }
            if (elapsed >= 20_000) {
                handleFailure(StreamError.TIMEOUT)
                break
            }
            if (s is LiveStreamState.Connecting) {
                liveState = LiveStreamState.Connecting(elapsed)
            }
        }
    }

    val exoPlayer =
        remember(url, retryTrigger) {
            Log.i(TAG, "[DEBUG-RTSP] Connecting to URL: $url")
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
                            Log.d(TAG, "[DEBUG-RTSP] State → $name (url=${maskRtspUrl(url)})")
                            if (state == Player.STATE_BUFFERING && liveState !is LiveStreamState.Playing) {
                                liveState = LiveStreamState.Buffering
                            }
                        }

                        override fun onRenderedFirstFrame() {
                            videoRevealed = true
                            liveState = LiveStreamState.Playing
                        }

                        override fun onPlayerError(e: PlaybackException) {
                            Log.e(TAG, "[DEBUG-RTSP] Error on ${maskRtspUrl(url)}: code=${e.errorCode} — ${e.message}", e)
                            e.cause?.let { cause ->
                                Log.e(TAG, "[DEBUG-RTSP]   caused by: ${cause.javaClass.simpleName}: ${cause.message}")
                            }
                            handleFailure(classifyError(e))
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
                            autoReconnectAttempts = 0
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

        StreamOverlay(
            state = liveState,
            posterUrl = snapshotUrl,
            showPoster = showLastImageWhileLoading,
            videoRevealed = videoRevealed,
            onRetry = {
                autoReconnectAttempts = 0
                retryTrigger++
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Mute toggle
        IconButton(
            onClick = { isMuted = !isMuted },
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).testTag("rtsp_mute_button"),
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
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 8.dp).testTag("rtsp_fullscreen_button"),
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
