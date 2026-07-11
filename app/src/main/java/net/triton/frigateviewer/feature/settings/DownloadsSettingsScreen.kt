package net.triton.frigateviewer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Clip export itself already works (Event Detail's overflow menu -> "Export clip", via
 * DownloadManager into the device's public Downloads folder) — there's nothing left to
 * configure here. This page exists to say so rather than sit as a silent, confusing empty page.
 */
@Composable
fun DownloadsSettingsScreen(onBack: () -> Unit) {
    SettingsSubPageScaffold(title = "Downloads", onBack = onBack) {
        SettingsSectionHeader("Clip downloads")

        SettingsCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    "Export a clip from an event's ⋮ menu → \"Export clip\". It's saved to your " +
                        "device's Downloads folder as frigate_<event-id>.mp4 — storage and cleanup are " +
                        "handled by your device, the same as any other download.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
