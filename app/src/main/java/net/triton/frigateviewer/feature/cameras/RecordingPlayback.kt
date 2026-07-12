package net.triton.frigateviewer.feature.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.triton.frigateviewer.R
import net.triton.frigateviewer.core.model.FrigateEvent
import okhttp3.OkHttpClient
import java.util.Locale

/**
 * How long a recording opened from a bare timestamp (a timeline tap, which has no end) runs before
 * the VOD window closes. Long enough to keep watching past the moment of interest without asking
 * Frigate to assemble an unbounded playlist.
 */
private const val TIMELINE_PLAYBACK_WINDOW_SECONDS = 10 * 60L

/** A few seconds of lead-in, so an event doesn't open mid-motion with no context. */
private const val EVENT_PLAYBACK_LEAD_SECONDS = 5L

/**
 * What the focused view plays instead of the live stream: a span of recorded footage, opened either
 * from an event in the side panel or from a tap on the timeline.
 */
data class PlaybackTarget(
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    /** Present when this came from an event; drives the label shown over the player. */
    val label: String? = null,
) {
    companion object {
        fun forEvent(event: FrigateEvent): PlaybackTarget {
            val start = event.startTime.toLong() - EVENT_PLAYBACK_LEAD_SECONDS
            // An event still in progress has no endTime yet — play up to now.
            val end = event.endTime?.toLong() ?: (System.currentTimeMillis() / 1000)
            return PlaybackTarget(
                startEpochSeconds = start,
                endEpochSeconds = maxOf(end, start + 1),
                label = event.label,
            )
        }

        fun forTime(epochSeconds: Long): PlaybackTarget {
            val nowSeconds = System.currentTimeMillis() / 1000
            return PlaybackTarget(
                startEpochSeconds = epochSeconds,
                // Never ask for footage from the future: Frigate has none, and the playlist errors.
                endEpochSeconds = minOf(epochSeconds + TIMELINE_PLAYBACK_WINDOW_SECONDS, nowSeconds),
            )
        }
    }
}

/**
 * Plays recorded footage in place of the live stream, inside the focused view.
 *
 * Deliberately in-place rather than a navigation to the Event Detail screen: the user opened this
 * from the side panel of a fullscreen live view and expects to stay exactly there — same fullscreen,
 * same orientation, same panel — with only the picture changing. Navigating away would lose all of
 * it. Fullscreen state is owned by FocusedTile precisely so this swap cannot disturb it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun RecordingPlayback(
    baseUrl: String,
    cameraName: String,
    target: PlaybackTarget,
    okHttpClient: OkHttpClient,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val vodUrl =
        remember(baseUrl, cameraName, target) {
            baseUrl.trimEnd('/') +
                "/vod/$cameraName/start/${target.startEpochSeconds}/end/${target.endEpochSeconds}/master.m3u8"
        }

    var buffering by remember(vodUrl) { mutableStateOf(true) }
    var error by remember(vodUrl) { mutableStateOf<String?>(null) }

    val player =
        remember(vodUrl) {
            // The authenticated per-server client, so the VOD request carries the session cookie —
            // a bare data source 401s against an authenticated Frigate.
            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            ExoPlayer
                .Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(vodUrl))
                    prepare()
                    playWhenReady = true
                }
        }

    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    buffering = state == Player.STATE_BUFFERING
                }

                override fun onPlayerError(e: PlaybackException) {
                    error = e.errorCodeName
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                // TextureView (not PlayerView's default SurfaceView) so the player composites with
                // the rest of the UI — a SurfaceView punches through the window and would paint over
                // the side panel sitting next to it.
                (
                    android.view.LayoutInflater
                        .from(ctx)
                        .inflate(R.layout.player_texture_view, null) as PlayerView
                ).apply {
                    this.player = player
                    useController = true
                }
            },
            // Required, not cosmetic: `factory` runs once, but `player` is remembered per vodUrl, so
            // choosing a different event (or tapping a new point on the timeline) builds a NEW
            // ExoPlayer while this PlayerView stayed bound to the old — now-released — one. That is
            // why a second selection appeared to "not close" the first: the view was still showing a
            // dead player. Rebinding on every update keeps the surface pointed at the live instance.
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && error == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
        }

        error?.let { reason ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Couldn't play this recording",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
                Text(reason, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                TextButton(onClick = onClose) { Text("Back to live") }
            }
        }

        // Header mirrors the live view's chrome: back arrow at the leading edge, mode indicator at
        // the trailing edge where live shows its red/green LIVE dot — so the two views read the same
        // and the indicator answers "am I watching live or a recording?" in the same glance.
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("playback_close_button")) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to live",
                    tint = Color.White,
                )
            }
            target.label?.let { label -> Pill(label.replaceFirstChar { it.uppercase(Locale.US) }) }
            Pill(clockLabel(target))
            Spacer(Modifier.weight(1f))
            PlaybackIndicatorPill(onClick = onClose)
        }
    }
}

/** Translucent pill, matching the camera/protocol pills the live view already uses. */
@Composable
private fun Pill(text: String) {
    Box(
        Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
    }
}

/** Sits where live view shows its LIVE dot; tapping it returns to live. */
@Composable
private fun PlaybackIndicatorPill(onClick: () -> Unit) {
    Row(
        Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("playback_indicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
        Text("Playback", style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

private fun clockLabel(target: PlaybackTarget): String {
    val time =
        Instant
            .fromEpochSeconds(target.startEpochSeconds)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    return String.format(Locale.US, "%02d:%02d", time.hour, time.minute)
}
