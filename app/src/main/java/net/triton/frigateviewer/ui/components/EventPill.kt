package net.triton.frigateviewer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun EventPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/** Camera name pill — uses secondaryContainer, distinct from label (primary) and score (tertiary). */
@Composable
fun CameraPill(
    camera: String,
    modifier: Modifier = Modifier,
) {
    EventPill(
        text = camera,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    )
}

/**
 * Combined segmented pill: [label | score | zone], each segment a distinct color.
 * Clipped as a single pill shape — no gaps between segments.
 *
 * - "person" label → blue (Material Blue 800) for quick visual recognition
 * - Zone color → deterministic from zone name hash across 4 theme-aware container colors
 */
@Composable
fun SegmentedEventPill(
    label: String,
    score: String?,
    zone: String?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val isPerson = label.equals("person", ignoreCase = true)
    val labelBg = if (isPerson) Color(0xFF1565C0) else cs.primaryContainer
    val labelFg = if (isPerson) Color.White else cs.onPrimaryContainer

    val zoneColorPairs =
        listOf(
            cs.primaryContainer to cs.onPrimaryContainer,
            cs.secondaryContainer to cs.onSecondaryContainer,
            cs.tertiaryContainer to cs.onTertiaryContainer,
            cs.errorContainer to cs.onErrorContainer,
        )
    val (zoneBg, zoneFg) =
        zone?.let { zoneColorPairs[abs(it.hashCode()) % zoneColorPairs.size] }
            ?: (Color.Transparent to Color.Transparent)

    Row(modifier = modifier.clip(RoundedCornerShape(50))) {
        Box(Modifier.background(labelBg).padding(horizontal = 6.dp, vertical = 3.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = labelFg)
        }
        if (score != null) {
            Box(Modifier.background(cs.tertiaryContainer).padding(horizontal = 6.dp, vertical = 3.dp)) {
                Text(score, style = MaterialTheme.typography.labelSmall, color = cs.onTertiaryContainer)
            }
        }
        if (zone != null) {
            Box(Modifier.background(zoneBg).padding(horizontal = 6.dp, vertical = 3.dp)) {
                Text(zone, style = MaterialTheme.typography.labelSmall, color = zoneFg)
            }
        }
    }
}
