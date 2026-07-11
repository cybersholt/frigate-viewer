package net.triton.frigateviewer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Small colored label grouping a section of rows within a sub-page — ported from PixelPlayer's
 * `SettingsSubsection` title style (e.g. "Global Theme" / "Now Playing" in their Appearance
 * screen). Mixed case, not uppercased — PixelPlayer's headers read as normal title text, just
 * small and tinted, not a shouty all-caps label.
 */
@Composable
internal fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Tonal card shared by every settings row — ported from PixelPlayer's row-as-card pattern
 * (`example_settings_002/003.png`: each row sits in its own rounded, tonally-shaded card, not a
 * flat list). Exposed `internal` so custom row content that doesn't go through `SettingRow`/
 * `SwitchSetting` (e.g. `AppearanceSettingsScreen`'s color-swatch and slider rows) can still match.
 */
@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SettingsRowIcon(icon: ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
    )
}

/**
 * Shared row layout: an optional leading icon (plain, top-aligned — PixelPlayer only uses colored
 * icon circles on its root settings list, not on sub-page rows), a title, and a value pill trailing
 * on the right, vertically centered against the row — same position `SwitchSetting`'s `Switch`
 * sits in, so picker rows and toggle rows read as visually balanced against each other. The pill
 * is a plain clickable `Surface`, not a Material `Button` — `Button`/`TextButton` enforce a ~58dp
 * minimum width, which made single-character values (e.g. "2" reconnect attempts) render as a
 * huge, mostly-empty pill.
 */
@Composable
internal fun SettingRow(
    title: String,
    icon: ImageVector? = null,
    value: String,
    onClick: () -> Unit,
) {
    SettingsCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) SettingsRowIcon(icon)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun SwitchSetting(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) SettingsRowIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (description != null) {
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/**
 * Full-screen-width option list in a bottom sheet — ported from PixelPlayer's "App Theme" picker
 * (`example_settings_004.png`... the one showing Light/Dark/Follow System, selected row filled
 * with the accent color and a trailing checkmark). Generic over `T` so it covers string, int, and
 * enum-backed settings alike without each call site re-deriving the same list UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> OptionPickerSheet(
    title: String,
    options: List<T>,
    selected: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    onClick = { onSelect(option) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            labelFor(option),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A `SettingRow` whose value pill opens an `OptionPickerSheet` — the shape every simple
 * value-list setting in this app actually needs (refresh interval, stream type, grid columns,
 * etc.), replacing the small `DropdownMenu` popups each screen used to hand-roll.
 */
@Composable
internal fun <T> PickerSettingRow(
    title: String,
    icon: ImageVector? = null,
    value: T,
    options: List<T>,
    labelFor: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    SettingRow(title = title, icon = icon, value = labelFor(value), onClick = { showSheet = true })
    if (showSheet) {
        OptionPickerSheet(
            title = title,
            options = options,
            selected = value,
            labelFor = labelFor,
            onSelect = {
                onSelect(it)
                showSheet = false
            },
            onDismiss = { showSheet = false },
        )
    }
}
