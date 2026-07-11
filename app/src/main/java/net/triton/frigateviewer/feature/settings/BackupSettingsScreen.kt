package net.triton.frigateviewer.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

/**
 * Export/import for app preferences (#15) — appearance, camera grid/streaming defaults, and event
 * display settings. Deliberately scoped to preferences only: servers and credentials live in
 * ServerRepository/CredentialStore, a separate system, and are never included in the exported
 * file — see UserSettingsRepository.exportPreferencesJson's doc for the exact boundary.
 */
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val json = vm.exportSettingsJson()
                val wrote =
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    }.isSuccess
                Toast
                    .makeText(
                        context,
                        if (wrote) "Settings exported" else "Export failed",
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val json =
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    }.getOrNull()
                val applied = json != null && vm.importSettingsJson(json)
                Toast
                    .makeText(
                        context,
                        if (applied) "Settings imported" else "Import failed — not a recognized settings file",
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }

    SettingsSubPageScaffold(title = "Backup & Restore", onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsSectionHeader("App preferences")
            Text(
                "Saves appearance, camera grid, and streaming preferences to a file you can restore later " +
                    "or move to another device. Servers and credentials are never included.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionSetting(
                title = "Export settings",
                description = "Save current preferences to a file.",
                icon = Icons.Filled.CloudUpload,
                actionLabel = "Export",
                onClick = { exportLauncher.launch("frigate-viewer-settings.json") },
            )
            ActionSetting(
                title = "Import settings",
                description = "Restore preferences from a previously exported file.",
                icon = Icons.Filled.CloudDownload,
                actionLabel = "Import",
                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
            )
        }
    }
}
