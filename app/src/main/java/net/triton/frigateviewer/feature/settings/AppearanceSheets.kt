package net.triton.frigateviewer.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle

private val paletteStyleInfo: Map<PaletteStyle, Pair<String, String>> =
    mapOf(
        PaletteStyle.TonalSpot to
            (
                "Tonal Spot" to
                    "Balanced tonal palette — the Material You default. Generates a calm, cohesive set of tones around your seed color."
            ),
        PaletteStyle.Neutral to
            ("Neutral" to "Muted and understated. Keeps colors subtle, letting content take center stage."),
        PaletteStyle.Vibrant to
            ("Vibrant" to "Punchy, saturated accents. Maximizes color expression from your seed."),
        PaletteStyle.Expressive to
            ("Expressive" to "Bold and asymmetric. Applies creative hue shifts for a dynamic look."),
        PaletteStyle.Rainbow to
            ("Rainbow" to "Wide multi-hue spread. Spreads color across a broad range of hues."),
        PaletteStyle.FruitSalad to
            ("Fruit Salad" to "Alternating hue-shifted tones. Creates playful contrast between surface layers."),
        PaletteStyle.Monochrome to
            ("Monochrome" to "Pure black, white, and gray. No color — maximum focus and minimal distraction."),
        PaletteStyle.Fidelity to
            ("Fidelity" to "Stays closest to your exact seed color. Great when brand color accuracy matters."),
        PaletteStyle.Content to
            (
                "Content" to
                    "Derived from content rather than a fixed seed. Best when using wallpaper or image-based theming."
            ),
    )

/** Display label for a stored [PaletteStyle] name (e.g. "TonalSpot" -> "Tonal Spot"), for use
 *  outside this file's own picker sheet — e.g. the value pill on the Appearance settings row. */
fun paletteStyleLabel(value: String): String =
    paletteStyleInfo[PaletteStyle.entries.find { it.name == value }]?.first ?: value

data class CardShapeOption(
    val label: String,
    val radiusDp: Int,
)

val cardShapeOptions =
    listOf(
        CardShapeOption("Sharp", 0),
        CardShapeOption("Extra Small", 4),
        CardShapeOption("Small", 8),
        CardShapeOption("Medium", 12),
        CardShapeOption("Large", 16),
        CardShapeOption("Extra Large", 28),
        CardShapeOption("Pill", 50),
    )

val presetAccentColors =
    listOf(
        0xFF6750A4L,
        0xFF006494L,
        0xFF006B5BL,
        0xFF7D5260L,
        0xFFB52700L,
        0xFF3D6635L,
        0xFF0057B7L,
        0xFFFF9800L,
        0xFFE91E63L,
        0xFF9C27B0L,
        0xFF2196F3L,
        0xFF4CAF50L,
        0xFFFF5722L,
        0xFF795548L,
        0xFF607D8BL,
        0xFF009688L,
        0xFFCDDC39L,
        0xFF673AB7L,
        0xFF3F51B5L,
        0xFF00BCD4L,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteStyleSheet(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                "Palette Style",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()
            PaletteStyle.entries.forEach { style ->
                val (name, description) = paletteStyleInfo[style] ?: (style.name to "")
                val selected = style.name == current
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(style.name)
                                onDismiss()
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardShapeSheet(
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Text(
                "Card Shape",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(200.dp),
            ) {
                items(cardShapeOptions) { option ->
                    val selected = option.radiusDp == current
                    Column(
                        modifier =
                            Modifier
                                .clickable {
                                    onSelect(option.radiusDp)
                                    onDismiss()
                                }.padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.4f)
                                .clip(RoundedCornerShape(option.radiusDp.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ).then(
                                    if (selected) {
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(option.radiusDp.dp),
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.Center).size(16.dp),
                                )
                            }
                        }
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun AccentColorRow(
    currentColor: Long,
    customColors: List<Long>,
    onSelectColor: (Long) -> Unit,
    onAddCustom: () -> Unit,
    onRemoveCustom: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Preset colors",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presetAccentColors) { color ->
                ColorDot(
                    color = color,
                    selected = currentColor == color,
                    onClick = { onSelectColor(color) },
                )
            }
        }
        if (customColors.isNotEmpty()) {
            Text(
                "Custom colors",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(customColors) { color ->
                    Box {
                        ColorDot(
                            color = color,
                            selected = currentColor == color,
                            onClick = { onSelectColor(color) },
                        )
                        IconButton(
                            onClick = { onRemoveCustom(color) },
                            modifier = Modifier.size(16.dp).align(Alignment.TopEnd),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove",
                                modifier = Modifier.size(10.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                item {
                    Box(
                        modifier =
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { onAddCustom() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add custom color", modifier = Modifier.size(18.dp))
                    }
                }
            }
        } else {
            TextButton(onClick = onAddCustom) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add custom color")
            }
        }
    }
}

@Composable
private fun ColorDot(
    color: Long,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(color.toInt()))
                .then(
                    if (selected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier
                    },
                ).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCustomColorSheet(
    onAdd: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        var hexInput by remember { mutableStateOf("") }
        val parsedColor =
            remember(hexInput) {
                hexInput
                    .removePrefix("#")
                    .takeIf { it.length == 6 }
                    ?.toLongOrNull(16)
                    ?.or(0xFF000000L)
            }

        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add Custom Color", style = MaterialTheme.typography.titleLarge)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it.take(7) },
                    label = { Text("Hex color") },
                    placeholder = { Text("#RRGGBB") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (parsedColor != null) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(parsedColor.toInt())),
                    )
                }
            }
            Text(
                "Or pick from more presets:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(100.dp),
            ) {
                items(presetAccentColors) { color ->
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(color.toInt()))
                            .clickable {
                                onAdd(color)
                                onDismiss()
                            },
                    )
                }
            }
            TextButton(
                onClick = {
                    if (parsedColor != null) {
                        onAdd(parsedColor)
                        onDismiss()
                    }
                },
                enabled = parsedColor != null,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Add")
            }
        }
    }
}
