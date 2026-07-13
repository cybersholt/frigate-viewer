package net.triton.frigateviewer.feature.cameras

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val POSTER_FADE_MS = 150

/**
 * Shared fullscreen/focused live-tile overlay: poster image (fades out on first real frame) +
 * centered spinner/label for every non-terminal state, or an error card with Retry. Draw this
 * on top of the actual player surface (WebRTC SurfaceViewRenderer / ExoPlayer PlayerView).
 */

/** 85% + 10 points of creep = 95%, the ceiling for "connected but no frame yet". */
private const val BUFFERING_CREEP_MAX_POINTS = 13

/**
 * Material 3 Expressive wavy progress for a connecting stream.
 *
 * The percentage is **connection phase**, not bytes: a live stream has no total to divide by, so
 * there is no honest byte-wise percentage to show. What it does encode is real — how far through the
 * handshake we are (dial → SDP negotiate → first frames buffering) — which is exactly the question
 * someone staring at a spinner is asking. Recorded playback is different and gets a genuine
 * percentage from ExoPlayer's buffered position; see RecordingPlayback.
 *
 * Reconnecting deliberately spins indeterminate: we're retrying, not progressing.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionProgress(state: LiveStreamState) {
    val phase =
        when (state) {
            is LiveStreamState.Idle -> 0.10f
            is LiveStreamState.Connecting -> 0.30f
            is LiveStreamState.Negotiating -> 0.60f
            is LiveStreamState.Buffering -> 0.85f
            else -> null
        }

    if (phase == null) {
        // Reconnecting / anything else: no meaningful progress to report.
        LoadingIndicator(color = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(56.dp))
        return
    }

    // Buffering is the last phase before frames arrive, so the ring would otherwise park at 85% for
    // as long as the first frame takes and read as stalled. Creep a point a second while we wait,
    // capped short of 100%: the last points are spent only by an actual frame arriving, so the ring
    // can never claim to be finished while the picture is still black.
    var creep by remember(state is LiveStreamState.Buffering) { mutableIntStateOf(0) }
    LaunchedEffect(state is LiveStreamState.Buffering) {
        if (state !is LiveStreamState.Buffering) return@LaunchedEffect
        while (creep < BUFFERING_CREEP_MAX_POINTS) {
            delay(100)
            creep++
        }
    }
    val target = (phase + creep / 100f).coerceAtMost(0.95f)

    // Animated so the ring eases between phases instead of snapping — the phases are coarse, and
    // motion is what makes them read as progress rather than as a stuttering gauge.
    val animatedPhase by animateFloatAsState(targetValue = target, animationSpec = tween(450), label = "connectPhase")

    Box(contentAlignment = Alignment.Center) {
        CircularWavyProgressIndicator(
            progress = { animatedPhase },
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
            modifier = Modifier.size(56.dp),
        )
        Text(
            "${(animatedPhase * 100).roundToInt()}%",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
fun StreamOverlay(
    state: LiveStreamState,
    posterUrl: String?,
    showPoster: Boolean,
    videoRevealed: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val posterAlpha by animateFloatAsState(
        targetValue = if (videoRevealed) 0f else 1f,
        animationSpec = tween(POSTER_FADE_MS),
        label = "posterAlpha",
    )

    Box(modifier.fillMaxSize()) {
        if (posterAlpha > 0f) {
            if (showPoster && posterUrl != null) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(LocalContext.current)
                            .data(posterUrl)
                            .crossfade(false)
                            .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().alpha(posterAlpha),
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f * posterAlpha)))
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = posterAlpha)),
                )
            }
        }

        when (state) {
            is LiveStreamState.Idle,
            is LiveStreamState.Connecting,
            is LiveStreamState.Negotiating,
            is LiveStreamState.Buffering,
            is LiveStreamState.Reconnecting,
            -> {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConnectionProgress(state)
                    Text(
                        labelFor(state),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            is LiveStreamState.Playing -> { /* video surface visible, no overlay */ }

            is LiveStreamState.Error -> {
                // Fully opaque (not the translucent scrim other states use) — this is the
                // terminal "we've declared this stream dead" state. A stalled RTSP connection can
                // silently resume rendering after the fact (see handleFailure's playerRef.stop()
                // in RtspLiveTile), and a translucent overlay let those stray frames show through
                // underneath "Live view unavailable" (backlog #6).
                Box(Modifier.fillMaxSize().background(Color.Black))
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.VideocamOff,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Text(
                        "Live view unavailable",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        supportingTextFor(state.reason),
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilledTonalButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

private fun labelFor(state: LiveStreamState): String =
    when (state) {
        is LiveStreamState.Idle -> {
            "Connecting…"
        }

        is LiveStreamState.Connecting -> {
            if (state.elapsedMs >= 8_000) "Still connecting — the camera may be waking up" else "Connecting…"
        }

        is LiveStreamState.Negotiating -> {
            "Negotiating…"
        }

        is LiveStreamState.Buffering -> {
            "Buffering…"
        }

        is LiveStreamState.Reconnecting -> {
            "Reconnecting (attempt ${state.attempt})…"
        }

        else -> {
            "Connecting…"
        }
    }

private fun supportingTextFor(reason: StreamError): String =
    when (reason) {
        StreamError.TIMEOUT -> "The camera took too long to respond."

        StreamError.ICE_FAILED -> "Couldn't establish a live connection."

        StreamError.SOURCE_UNAVAILABLE -> "The camera source is unavailable."

        StreamError.DECODE_FAILED -> "This device couldn't decode the video."

        StreamError.NETWORK -> "Network error reaching the server."

        // Normally never seen: StreamContent switches the camera to WebRTC as soon as the RTSP tile
        // reports this, so the tile is replaced rather than left sitting on an error. This copy only
        // surfaces if that fallback can't run.
        StreamError.RTSP_UNSUPPORTED -> "RTSP can't play this camera's stream. Switching to WebRTC."
    }
