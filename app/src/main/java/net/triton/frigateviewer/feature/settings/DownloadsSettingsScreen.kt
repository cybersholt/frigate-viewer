package net.triton.frigateviewer.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * No download preferences exist yet anywhere in the app — nothing to move here. Placeholder
 * page reserves the nav slot; wire up real settings once a downloads feature exists.
 */
@Composable
fun DownloadsSettingsScreen(onBack: () -> Unit) {
    SettingsSubPageScaffold(title = "Downloads", onBack = onBack) {
        Text(
            "No download settings yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
