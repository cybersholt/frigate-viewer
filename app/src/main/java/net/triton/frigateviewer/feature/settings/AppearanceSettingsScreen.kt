package net.triton.frigateviewer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.triton.frigateviewer.ui.theme.ThemeMode

@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showPaletteSheet by remember { mutableStateOf(false) }
    var showShapeSheet by remember { mutableStateOf(false) }
    var showCustomColorPicker by remember { mutableStateOf(false) }

    SettingsSubPageScaffold(title = "Appearance", onBack = onBack) {
        SettingsSectionHeader("Theme")

        SettingRow(title = "Color mode") {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = true }) { Text(state.themeMode) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ThemeMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.name) },
                        onClick = {
                            vm.setThemeMode(mode.name)
                            expanded = false
                        },
                    )
                }
            }
        }

        AccentColorRow(
            currentColor = state.accentColor,
            customColors = state.customAccentColors,
            onSelectColor = { vm.setAccentColor(it) },
            onAddCustom = { showCustomColorPicker = true },
            onRemoveCustom = { vm.removeCustomAccentColor(it) },
        )

        SwitchSetting(
            title = "Use wallpaper color",
            checked = state.useWallpaperColor,
            onCheckedChange = vm::setUseWallpaperColor,
        )

        SettingRow(title = "Palette style") {
            TextButton(onClick = { showPaletteSheet = true }) { Text(state.paletteStyle) }
        }

        SwitchSetting(
            title = "AMOLED black",
            checked = state.amoledBlack,
            onCheckedChange = vm::setAmoledBlack,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Contrast", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (state.contrastLevel >= 0) "+${state.contrastLevel}" else "${state.contrastLevel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = state.contrastLevel.toFloat(),
                onValueChange = { vm.setContrastLevel(it.toInt()) },
                valueRange = -100f..100f,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SettingsSectionHeader("Card Style")

        SettingRow(title = "Card shape") {
            TextButton(onClick = { showShapeSheet = true }) {
                Text(cardShapeOptions.find { it.radiusDp == state.cardCornerRadius }?.label ?: "Medium")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Border width", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (state.cardBorderWidth == 0) "Off" else "${state.cardBorderWidth}dp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = state.cardBorderWidth.toFloat(),
                onValueChange = { vm.setCardBorderWidth(it.toInt()) },
                valueRange = 0f..8f,
                steps = 7,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showPaletteSheet) {
        PaletteStyleSheet(
            current = state.paletteStyle,
            onSelect = vm::setPaletteStyle,
            onDismiss = { showPaletteSheet = false },
        )
    }

    if (showShapeSheet) {
        CardShapeSheet(
            current = state.cardCornerRadius,
            onSelect = vm::setCardCornerRadius,
            onDismiss = { showShapeSheet = false },
        )
    }

    if (showCustomColorPicker) {
        AddCustomColorSheet(
            onAdd = vm::addCustomAccentColor,
            onDismiss = { showCustomColorPicker = false },
        )
    }
}
