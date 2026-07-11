package net.triton.frigateviewer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.triton.frigateviewer.BuildConfig

@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    SettingsSubPageScaffold(title = "About", onBack = onBack) {
        SettingsSectionHeader("App")

        SettingsCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Frigate Viewer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}${if (BuildConfig.DEBUG) " (debug)" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SettingsCard {
            Text(
                "Unofficial native Android client for Frigate NVR. Not affiliated with Frigate.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SettingsCard {
            Text(
                "Credits: built on Media3, WebRTC, Coil, and Retrofit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
