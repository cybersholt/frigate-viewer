package net.triton.frigateviewer.feature.cameras

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

        is LiveStreamState.Error -> CameraStreamState.Offline(reason.name)
    }
