@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")

package net.triton.frigateviewer.feature.cameras

import android.util.Log
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.triton.frigateviewer.LocalIsInPip

private const val TAG = "RtspLiveTile"
private const val MAX_BACKOFF_MS = 30_000L

/** Sampling cadence for the Developer Options stats overlay. */
private const val RTSP_STATS_INTERVAL_MS = 1_000L

/** Exponential backoff (base, base×2, base×4, ...) capped at [MAX_BACKOFF_MS]. */
private fun backoffMs(
    attempt: Int,
    baseDelaySeconds: Int,
): Long = (baseDelaySeconds * 1000L * (1L shl (attempt - 1))).coerceAtMost(MAX_BACKOFF_MS)

private fun maskRtspUrl(url: String): String = url.replace(Regex("(rtsp://[^:@/]+):([^@]+)@"), "$1:***@")

/**
 * True when the failure is ExoPlayer refusing the stream's SDP outright rather than any kind of
 * network trouble. Matched on the exception chain's message because Media3 surfaces it as a generic
 * ERROR_CODE_IO_UNSPECIFIED (2000) `Source error` — the only thing distinguishing it is the
 * IllegalArgumentException("missing sprop parameter") buried in the cause chain.
 */
private fun PlaybackException.isMissingSpropParameter(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause.message?.contains("sprop", ignoreCase = true) == true) return true
        cause = cause.cause
    }
    return false
}

private fun classifyError(e: PlaybackException): StreamError =
    when {
        e.isMissingSpropParameter() -> StreamError.RTSP_UNSUPPORTED
        else -> classifyByErrorCode(e)
    }

private fun classifyByErrorCode(e: PlaybackException): StreamError =
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
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {},
    showLastImageWhileLoading: Boolean = true,
    showStreamStats: Boolean = false,
    /** From Frigate's config (cameras.<name>.audio.enabled). With audio off there is nothing to unmute. */
    audioEnabled: Boolean = false,
    onStateChanged: (LiveStreamState) -> Unit = {},
    onFatal: () -> Unit = {},
    /** ExoPlayer can't play this SDP at all (see [StreamError.RTSP_UNSUPPORTED]) — caller should switch transport. */
    onRtspUnsupported: () -> Unit = {},
) {
    val reconnectSettings = LocalRtspReconnectSettings.current
    val maxReconnectAttempts = reconnectSettings.maxAttempts
    val reconnectBaseDelaySeconds = reconnectSettings.baseDelaySeconds
    val context = LocalContext.current
    var retryTrigger by remember { mutableIntStateOf(0) }
    var autoReconnectAttempts by remember { mutableIntStateOf(0) }
    var isMuted by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }
    var videoRevealed by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var liveState by remember { mutableStateOf<LiveStreamState>(LiveStreamState.Idle) }
    LaunchedEffect(liveState) { onStateChanged(liveState) }

    // ── Developer Options: stream telemetry ──
    // ExoPlayer exposes bitrate as the *declared* Format.bitrate, which for a live RTSP feed is
    // often Format.NO_VALUE, so bandwidth is measured the same way as the WebRTC tile: as the
    // derivative of a cumulative byte counter. There isn't one on ExoPlayer, so we fall back to the
    // declared bitrate when present and otherwise report nothing rather than inventing a number.
    var statsSample by remember { mutableStateOf<StreamStats?>(null) }
    var statsHistory by remember { mutableStateOf(StreamStatsHistory()) }

    // Real measured throughput, when media3 reports transfers for this media source. RTSP's RTP data
    // channels don't always feed the bandwidth meter, in which case bitrateEstimate stays at its
    // seeded default — which would be a fabricated number, not a measurement. So only trust it once
    // an actual sample has been observed, and otherwise report nothing.
    val bandwidthMeter = remember { DefaultBandwidthMeter.Builder(context).build() }
    var sawBandwidthSample by remember { mutableStateOf(false) }
    DisposableEffect(bandwidthMeter) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val listener =
            BandwidthMeter.EventListener { _, bytesTransferred, _ ->
                if (bytesTransferred > 0) sawBandwidthSample = true
            }
        bandwidthMeter.addEventListener(handler, listener)
        onDispose { bandwidthMeter.removeEventListener(listener) }
    }

    // Holds the current ExoPlayer so handleFailure (defined before the player exists, since the
    // player's own error listener needs to call it) can stop it. Without this, declaring
    // Reconnecting/Error only flips the Compose state — the still-alive player keeps buffering
    // and can silently resume rendering frames behind the (translucent) error overlay.
    var playerRef by remember { mutableStateOf<ExoPlayer?>(null) }

    LaunchedEffect(showStreamStats, retryTrigger) {
        if (!showStreamStats) {
            statsSample = null
            statsHistory = StreamStatsHistory()
            return@LaunchedEffect
        }
        while (true) {
            delay(RTSP_STATS_INTERVAL_MS)
            val player = playerRef ?: continue
            val format = player.videoFormat
            val counters = player.videoDecoderCounters
            val declaredBitrate = format?.bitrate?.takeIf { it > 0 }

            // renderedOutputBufferCount + droppedBufferCount is the closest ExoPlayer equivalent of
            // WebRTC's framesReceived: frames the decoder actually accounted for, either way.
            val rendered = counters?.renderedOutputBufferCount?.toLong()
            val dropped = counters?.droppedBufferCount?.toLong()
            val total = if (rendered != null && dropped != null) rendered + dropped else null

            // Prefer the measured rate; fall back to the bitrate the stream declares in its Format
            // (frequently NO_VALUE on a live camera feed, hence nullable).
            val measuredKbps =
                if (sawBandwidthSample) {
                    bandwidthMeter.bitrateEstimate.takeIf { it > 0 }?.let { it / 1000.0 }
                } else {
                    null
                }

            val sample =
                StreamStats(
                    streamType = "rtsp",
                    bandwidthKbps = measuredKbps ?: declaredBitrate?.let { it / 1000.0 },
                    // RTSP over ExoPlayer surfaces no round-trip time.
                    latencyMs = null,
                    framesTotal = total,
                    framesDecoded = rendered,
                    framesDropped = dropped,
                    readAheadSeconds = player.totalBufferedDuration / 1000.0,
                    resolution =
                        format?.let { f ->
                            if (f.width > 0 && f.height > 0) "${f.width}x${f.height}" else null
                        },
                    codec = format?.sampleMimeType?.substringAfter('/'),
                )
            statsSample = sample
            statsHistory += sample
        }
    }

    // NETWORK and TIMEOUT are both treated as transient (a stalled/dropped connection commonly
    // surfaces as either depending on exactly when the socket gives up) — SOURCE_UNAVAILABLE
    // (bad HTTP status / no permission / cleartext blocked) and DECODE_FAILED are genuine
    // config/format problems that retrying won't fix.
    fun handleFailure(reason: StreamError) {
        playerRef?.stop()
        if (reason == StreamError.RTSP_UNSUPPORTED) {
            // Not a failure to retry or to surface as "offline": the stream is simply unplayable by
            // ExoPlayer's RTSP stack. Hand it back to the caller, which re-runs this camera on WebRTC.
            Log.w(TAG, "RTSP unplayable (no sprop-parameter-sets in SDP) for ${maskRtspUrl(url)} — falling back to WebRTC")
            liveState = LiveStreamState.Error(reason)
            onRtspUnsupported()
            return
        }
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
            ExoPlayer.Builder(context, renderersFactory).setBandwidthMeter(bandwidthMeter).build().apply {
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
    playerRef = exoPlayer

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    // Fullscreen/orientation/system-bars ownership lives in StreamContent (the stable call
    // site across protocol switches), not here — this composable is torn down and recreated
    // whenever the user switches protocol, which previously reset that state and left the
    // orientation lock stuck (see #5 verification session writeup).

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
            // factory only runs once per AndroidView slot — without this, switching cameras or
            // retrying (both of which remember() a brand-new exoPlayer instance keyed on
            // url/retryTrigger) leaves this PlayerView bound to the old, released player, so the
            // visible frame silently freezes on whatever was last rendered instead of showing the
            // new stream (confirmed live: camera-switch title updates but video stays on the old
            // camera's frame, burned-in "CH 1" watermark visible after switching to ch2).
            update = { it.player = exoPlayer },
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

        statsSample?.let { sample ->
            StreamStatsOverlay(
                stats = sample,
                history = statsHistory,
                // Clears the protocol/LIVE badge row above it; drag it anywhere from there.
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 6.dp),
            )
        }

        // Tile chrome is suppressed in PiP: the window is a few hundred pixels wide, so buttons meant
        // for a full-size tile just cover the picture. Matches WebRtcLiveTile, and Android's PiP
        // guidance (minimal chrome; the system supplies its own controls).
        if (!LocalIsInPip.current) {
            // Mute toggle — only when the camera actually publishes audio. Frigate's config says
            // whether it does; offering an unmute control for a silent camera is a dead button.
            if (audioEnabled) {
                IconButton(
                    onClick = { isMuted = !isMuted },
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).testTag("rtsp_mute_button"),
                ) {
                    Icon(
                        imageVector =
                            if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // Fullscreen toggle
            IconButton(
                onClick = onToggleFullScreen,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 8.dp)
                        .testTag("rtsp_fullscreen_button"),
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
}
