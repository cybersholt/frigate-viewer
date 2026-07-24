package net.triton.frigateviewer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.triton.frigateviewer.core.ui.LineAreaChart
import kotlin.math.roundToInt

private data class DetectorStat(
    val name: String,
    val inferenceMs: Double?,
)

private data class CameraStat(
    val name: String,
    val cameraFps: Double?,
    val detectionFps: Double?,
)

/**
 * Rolling series behind the charts, sampled from each stats poll while the screen is open.
 *
 * Rebuilt as an immutable snapshot per sample rather than mutated in place — same reasoning as
 * `StreamStatsHistory` in the live-tile overlay: at this window size the copy is free, and an
 * in-place mutation would not reliably trigger recomposition under Compose's snapshot system.
 *
 * Deliberately screen-scoped (held by `remember` in AdvancedSettingsScreen, not the ViewModel):
 * Frigate's `api/stats` is a snapshot with no history endpoint, so the series can only ever cover
 * the time the page has been open. Persisting it would imply a continuity the data doesn't have.
 */
data class SystemStatsHistory(
    val cpu: List<Double> = emptyList(),
    val gpu: List<Double> = emptyList(),
    val ram: List<Double> = emptyList(),
    val detectorInferenceMs: Map<String, List<Double>> = emptyMap(),
) {
    /** Appends one poll's worth of samples, dropping anything past [HISTORY_SIZE]. */
    fun plus(raw: JsonObject): SystemStatsHistory {
        val s = parseStats(raw)

        fun List<Double>.push(v: Double?): List<Double> = if (v == null) this else (this + v).takeLast(HISTORY_SIZE)

        return SystemStatsHistory(
            cpu = cpu.push(s.cpuPercent?.toDouble()),
            gpu = gpu.push(s.gpuPercent?.toDouble()),
            ram = ram.push(s.ramPercent?.toDouble()),
            detectorInferenceMs =
                s.detectors.associate { d ->
                    d.name to detectorInferenceMs[d.name].orEmpty().push(d.inferenceMs)
                },
        )
    }
}

/** ~2.5 min of history at the default 3s poll — enough to see a trend without crowding the line. */
private const val HISTORY_SIZE = 50

private data class ParsedStats(
    val cpuPercent: Int?,
    val gpuPercent: Int?,
    val ramPercent: Int?,
    val uptimeText: String?,
    val detectors: List<DetectorStat>,
    val cameras: List<CameraStat>,
)

/** Frigate's api/stats shape varies by version — every field here is best-effort and optional. */
private fun parseStats(root: JsonObject): ParsedStats {
    fun JsonObject.numOrNull(key: String): Double? =
        ((this[key] as? JsonPrimitive)?.content)?.trimEnd('%')?.toDoubleOrNull()

    // Frigate's cpu_usages includes a "frigate.full_system" entry (alongside per-process
    // entries) holding psutil.cpu_percent()/virtual_memory().percent — already normalized
    // 0-100% system-wide. Summing every per-process entry instead double-counts and can
    // exceed 100%, so use this entry directly rather than aggregating the others.
    val fullSystem = (root["cpu_usages"] as? JsonObject)?.get("frigate.full_system") as? JsonObject
    val cpuPercent = fullSystem?.numOrNull("cpu")?.roundToInt()?.coerceIn(0, 100)
    val ramPercent = fullSystem?.numOrNull("mem")?.roundToInt()?.coerceIn(0, 100)

    val gpuUsages = root["gpu_usages"] as? JsonObject
    val gpuPercent =
        gpuUsages
            ?.values
            ?.mapNotNull { (it as? JsonObject)?.numOrNull("gpu") }
            ?.takeIf { it.isNotEmpty() }
            ?.let { it.sum() / it.size }
            ?.roundToInt()

    val uptimeSeconds = (root["service"] as? JsonObject)?.numOrNull("uptime")?.toLong()
    val uptimeText =
        uptimeSeconds?.let { total ->
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            "${hours}h ${minutes}m"
        }

    val detectors =
        (root["detectors"] as? JsonObject)?.entries?.map { (name, value) ->
            DetectorStat(name, (value as? JsonObject)?.numOrNull("inference_speed"))
        } ?: emptyList()

    val cameras =
        (root["cameras"] as? JsonObject)?.entries?.map { (name, value) ->
            val obj = value as? JsonObject
            CameraStat(name, obj?.numOrNull("camera_fps"), obj?.numOrNull("detection_fps"))
        } ?: emptyList()

    return ParsedStats(cpuPercent, gpuPercent, ramPercent, uptimeText, detectors, cameras)
}

@Composable
fun SystemStatsOverview(
    raw: JsonObject,
    history: SystemStatsHistory = SystemStatsHistory(),
) {
    val stats = remember(raw) { parseStats(raw) }
    val cs = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                "CPU",
                stats.cpuPercent?.let { "$it%" },
                stats.cpuPercent,
                history.cpu,
                cs.primary,
                Modifier.weight(1f),
            )
            StatCard(
                "GPU",
                stats.gpuPercent?.let { "$it%" },
                stats.gpuPercent,
                history.gpu,
                cs.tertiary,
                Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                "RAM",
                stats.ramPercent?.let { "$it%" },
                stats.ramPercent,
                history.ram,
                cs.secondary,
                Modifier.weight(1f),
            )
            // Uptime deliberately has no chart — it only ever counts up, so the line would be a
            // diagonal saying nothing the number doesn't already.
            StatCard("Uptime", stats.uptimeText, null, emptyList(), cs.primary, Modifier.weight(1f))
        }

        if (stats.detectors.isNotEmpty()) {
            SettingsSectionHeader("Detectors")
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    stats.detectors.forEachIndexed { index, d ->
                        if (index > 0) HorizontalDivider()
                        StatRow(d.name, d.inferenceMs?.let { "%.1f ms".format(it) } ?: "—")
                        // Inference speed is the headline number on Frigate's own System page,
                        // so it gets the one chart in this section.
                        val series = history.detectorInferenceMs[d.name].orEmpty()
                        if (series.size >= 2) {
                            LineAreaChart(
                                values = series,
                                lineColor = cs.primary,
                                height = 40.dp,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                // Inference time has no fixed ceiling, but it's also very stable —
                                // scaled to its own peak the line pins to the top edge and the
                                // chart reads as a filled block. A little headroom above the peak
                                // keeps it legible as a line while still showing real variation.
                                maxValue = (series.max() * 1.35).takeIf { it > 0 },
                            )
                        }
                    }
                }
            }
        }

        if (stats.cameras.isNotEmpty()) {
            SettingsSectionHeader("Cameras")
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    stats.cameras.forEachIndexed { index, c ->
                        if (index > 0) HorizontalDivider()
                        val fpsText =
                            listOfNotNull(
                                c.cameraFps?.let { "%.1f fps".format(it) },
                                c.detectionFps?.let { "det %.1f".format(it) },
                            ).joinToString("  ·  ")
                        StatRow(c.name, fpsText.ifEmpty { "—" })
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String?,
    percent: Int?,
    history: List<Double>,
    chartColor: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value ?: "—", style = MaterialTheme.typography.headlineSmall)
            // The chart replaces the bar once there's a trend to show. Until two samples have
            // landed there is nothing to plot, so the bar carries the current value on its own —
            // that keeps the card from being empty for the first few seconds on the screen.
            if (history.size >= 2) {
                LineAreaChart(
                    values = history,
                    lineColor = chartColor,
                    height = 36.dp,
                    modifier = Modifier.fillMaxWidth(),
                    // These are all 0-100% readings, so pin the axis to 100 — the height then
                    // means the same thing as the number above it.
                    maxValue = 100.0,
                )
            } else if (percent != null) {
                LinearProgressIndicator(
                    progress = { (percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
