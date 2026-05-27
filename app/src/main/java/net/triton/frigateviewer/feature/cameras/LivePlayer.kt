package net.triton.frigateviewer.feature.cameras

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Generic HLS / RTSP / DASH live tile via Media3 ExoPlayer.
 *
 * For WebRTC sub-second live, use [WebRtcLivePlayer] (stream-webrtc-android) — wired
 * separately because peer connections are heavier and limited to ~4 concurrent on most
 * devices. Strategy: WebRTC for the focused tile, ExoPlayer HLS for grid tiles.
 */
@Composable
fun LivePlayer(
    streamUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(streamUrl) { buildPlayer(context, streamUrl) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                this.player = player
            }
        },
    )
}

private fun buildPlayer(
    context: Context,
    url: String,
): ExoPlayer {
    val player = ExoPlayer.Builder(context).build()
    player.setMediaItem(MediaItem.fromUri(url))
    player.prepare()
    player.playWhenReady = true
    player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
    return player
}
