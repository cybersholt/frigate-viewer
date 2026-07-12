package net.triton.frigateviewer.feature.cameras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

/** How many samples the sparklines keep. At the 1 Hz sample rate the tiles use, ~1 minute. */
private const val HISTORY_SIZE = 60

private const val EMPTY = "—"

/**
 * A single telemetry sample for a live stream, modelled on Frigate's own debug overlay.
 *
 * Every field is nullable because the two playback pipelines genuinely expose different things:
 * WebRTC (`PeerConnection.getStats`) reports round-trip time and full frame accounting but has no
 * notion of a read-ahead buffer, while ExoPlayer (RTSP) reports buffered duration and dropped
 * frames but no RTT. Rendering "0" for a number the transport cannot measure would be a lie, so
 * absent values render as "—".
 */
data class StreamStats(
    val streamType: String,
    val bandwidthKbps: Double? = null,
    val latencyMs: Double? = null,
    val framesTotal: Long? = null,
    val framesDecoded: Long? = null,
    val framesDropped: Long? = null,
    /** Seconds of media buffered ahead of the playhead. ExoPlayer only — WebRTC does not read ahead. */
    val readAheadSeconds: Double? = null,
    val resolution: String? = null,
    val codec: String? = null,
) {
    /** Fraction of frames lost, 0..1. Null when the transport doesn't report frame counts. */
    val droppedFrameRate: Double?
        get() {
            val total = framesTotal ?: return null
            val dropped = framesDropped ?: return null
            return if (total <= 0L) 0.0 else dropped.toDouble() / total.toDouble()
        }
}

/**
 * Rolling history for the sparklines.
 *
 * Rebuilt as an immutable snapshot per sample rather than mutated in place: at 60 samples the copy
 * is free, and an immutable value plays correctly with Compose's snapshot system (an in-place
 * mutation would not reliably trigger recomposition).
 */
data class StreamStatsHistory(
    val bandwidthKbps: List<Double> = emptyList(),
) {
    operator fun plus(sample: StreamStats): StreamStatsHistory =
        StreamStatsHistory(
            bandwidthKbps = (bandwidthKbps + (sample.bandwidthKbps ?: 0.0)).takeLast(HISTORY_SIZE),
        )
}

/**
 * Frigate-style debug overlay: a compact translucent card in the corner of a live tile showing the
 * current [stats] plus sparklines of recent bandwidth (and read-ahead, where the transport has one).
 *
 * The hardcoded dark chrome with white ink is intentional and is NOT a theming oversight: this
 * always sits on top of video, which is its own backdrop regardless of the app's light/dark theme.
 */
@Composable
fun StreamStatsOverlay(
    stats: StreamStats,
    history: StreamStatsHistory,
    modifier: Modifier = Modifier,
) {
    // Draggable, because a fixed corner will always be the wrong corner for somebody: the overlay
    // sits on top of live video and can cover exactly the part of the frame being investigated.
    // Offset is per-tile session state — it deliberately does not persist, so the overlay always
    // comes back where it's expected.
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier
            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    dragOffset += drag
                }
            }.width(190.dp)
            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        stats.resolution?.let { StatRow("Resolution", it) }
        stats.codec?.let { StatRow("Codec", it) }
        StatRow("Bandwidth", stats.bandwidthKbps?.let { formatBandwidth(it) } ?: EMPTY)
        StatRow("Latency", stats.latencyMs?.let { "${it.toInt()} ms" } ?: EMPTY)
        StatRow("Frames", stats.framesTotal?.toString() ?: EMPTY)
        StatRow("Decoded", stats.framesDecoded?.toString() ?: EMPTY)
        StatRow("Dropped", stats.framesDropped?.toString() ?: EMPTY)
        StatRow(
            label = "Drop rate",
            value = stats.droppedFrameRate?.let { String.format(Locale.US, "%.2f%%", it * 100) } ?: EMPTY,
            // >1% of frames lost is roughly where playback starts visibly hitching.
            warn = (stats.droppedFrameRate ?: 0.0) > 0.01,
        )
        stats.readAheadSeconds?.let {
            StatRow("Read-ahead", String.format(Locale.US, "%.1f s", it))
        }

        // Only chart bandwidth once something has actually been measured. Charting a series that is
        // all zeros (RTSP, where the transport reports no byte counter) drew an empty box that read
        // as "the chart is broken" rather than "this transport can't measure it".
        if (history.bandwidthKbps.any { it > 0.0 }) {
            Spacer(Modifier.height(4.dp))
            Sparkline(
                label = "Bandwidth",
                values = history.bandwidthKbps,
                lineColor = Color(0xFF4CAF50),
                formatPeak = { formatBandwidth(it) },
            )
        } else if (stats.bandwidthKbps == null) {
            Spacer(Modifier.height(3.dp))
            StatText(
                "Bandwidth not reported by this transport",
                Color.White.copy(alpha = 0.5f),
                Modifier.fillMaxWidth(),
            )
        }
        // No read-ahead chart: a live stream sits at ~1s of buffer by design, so the series is a
        // flat line that tells you nothing the numeric row above doesn't already say.
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    emphasis: Boolean = false,
    warn: Boolean = false,
) {
    Row(Modifier.fillMaxWidth()) {
        StatText(label, Color.White.copy(alpha = 0.65f), Modifier.weight(1f))
        StatText(
            text = value,
            color = if (warn) Color(0xFFEF5350) else Color.White,
            weight = if (emphasis || warn) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun StatText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        color = color,
        // Monospace so the numbers stop jittering sideways as digit widths change each second.
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        fontWeight = weight,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * YouTube-stats-style sparkline. The series is scaled to its own running peak (shown top-right), so
 * a stream sitting at a steady bitrate still shows real variation instead of a flat line pinned to
 * some arbitrary absolute scale.
 */
@Composable
private fun Sparkline(
    label: String,
    values: List<Double>,
    lineColor: Color,
    formatPeak: (Double) -> String,
) {
    // Peak only: the live value already has its own "Bandwidth" row above the chart, so repeating it
    // in the chart's header was redundant.
    val peak = values.maxOrNull() ?: 0.0
    Row(Modifier.fillMaxWidth()) {
        StatText(label, Color.White.copy(alpha = 0.65f), Modifier.weight(1f))
        StatText(
            text = if (peak > 0) "peak ${formatPeak(peak)}" else EMPTY,
            color = Color.White.copy(alpha = 0.65f),
        )
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(3.dp))
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        if (values.size < 2 || peak <= 0.0) return@Canvas
        val stepX = size.width / (values.size - 1).toFloat()
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v / peak).toFloat() * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = lineColor, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
        // Baseline, so an all-zero stretch reads as "measured zero" rather than "no data".
        drawLine(
            color = Color.White.copy(alpha = 0.15f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 0.5f,
        )
    }
}

private fun formatBandwidth(kbps: Double): String =
    if (kbps >= 1000) {
        String.format(Locale.US, "%.1f Mbps", kbps / 1000)
    } else {
        String.format(Locale.US, "%.0f kbps", kbps)
    }
