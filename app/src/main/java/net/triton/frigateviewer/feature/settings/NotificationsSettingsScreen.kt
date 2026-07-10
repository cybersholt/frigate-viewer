package net.triton.frigateviewer.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * No notification preferences exist yet anywhere in the app (grepped UserSettingsRepository +
 * the whole tree — nothing to move here). Placeholder page reserves the nav slot; wire up real
 * settings here once the notification feature grows configurable options.
 */
@Composable
fun NotificationsSettingsScreen(onBack: () -> Unit) {
    SettingsSubPageScaffold(title = "Notifications", onBack = onBack) {
        Text(
            "No notification settings yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
