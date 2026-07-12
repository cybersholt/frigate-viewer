package net.triton.frigateviewer.feature.cameras

import androidx.compose.runtime.staticCompositionLocalOf

/** User-configurable RTSP auto-reconnect behavior (Settings → Streaming). */
data class RtspReconnectSettings(
    val maxAttempts: Int = 2,
    val baseDelaySeconds: Int = 2,
)

/**
 * Provided once near the CamerasScreen root so [RtspLiveTile] can read the user's configured
 * reconnect behavior without threading two extra params through every intermediate composable
 * (StreamContent, grid-tile wrappers, etc.) between CamerasViewModel's state and the player.
 */
val LocalRtspReconnectSettings = staticCompositionLocalOf { RtspReconnectSettings() }

/**
 * Connection state for a single live-player instance (WebRTC/RTSP/Snapshot), owned by the
 * player composable and observed by [net.triton.frigateviewer.feature.cameras.StreamOverlay].
 * Distinct from [CameraStreamState], which is the simpler badge/grid-tile snapshot-polling
 * state — this type exists only for the fullscreen/focused live view.
 */
sealed interface LiveStreamState {
    data object Idle : LiveStreamState

    data class Connecting(
        val elapsedMs: Long,
    ) : LiveStreamState

    data object Negotiating : LiveStreamState

    data object Buffering : LiveStreamState

    data object Playing : LiveStreamState

    data class Reconnecting(
        val attempt: Int,
    ) : LiveStreamState

    data class Error(
        val reason: StreamError,
    ) : LiveStreamState
}

enum class StreamError {
    TIMEOUT,
    ICE_FAILED,
    SOURCE_UNAVAILABLE,
    DECODE_FAILED,
    NETWORK,

    /**
     * ExoPlayer's RTSP stack cannot play this stream at all: the SDP advertises no
     * `sprop-parameter-sets` (out-of-band SPS/PPS), which its H.264 RTP reader requires before it
     * will start — it fails with "missing sprop parameter". Seen on go2rtc restreams of Wyze
     * cameras, whose source only emits parameter sets in-band; ffmpeg tolerates that, ExoPlayer
     * does not.
     *
     * This is a property of the stream, so retrying is pointless. WebRTC plays the same camera
     * fine, so StreamContent falls back to it rather than showing the tile as offline.
     */
    RTSP_UNSUPPORTED,
}

/** Collapses the rich live-player state down to the small corner badge's simpler vocabulary. */
fun LiveStreamState.toBadgeState(): CameraStreamState =
    when (this) {
        is LiveStreamState.Idle,
        is LiveStreamState.Connecting,
        is LiveStreamState.Negotiating,
        is LiveStreamState.Buffering,
        is LiveStreamState.Reconnecting,
        -> CameraStreamState.Skeleton

        is LiveStreamState.Playing -> CameraStreamState.Live

        is LiveStreamState.Error -> CameraStreamState.Offline
    }
