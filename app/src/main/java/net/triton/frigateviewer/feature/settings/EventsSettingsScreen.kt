package net.triton.frigateviewer.feature.settings

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun EventsSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    SettingsSubPageScaffold(title = "Events", onBack = onBack) {
        SettingRow(title = "Events grid") {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = true }) { Text("${state.eventGridColumns}x") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf(1, 2, 3).forEach { cols ->
                    DropdownMenuItem(
                        text = { Text("${cols}x") },
                        onClick = {
                            vm.setEventGridColumns(cols)
                            expanded = false
                        },
                    )
                }
            }
        }

        SettingRow(title = "Photo preference") {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = true }) { Text(state.eventPhotoPreference) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf("snapshot", "thumbnail").forEach { pref ->
                    DropdownMenuItem(
                        text = { Text(pref) },
                        onClick = {
                            vm.setEventPhotoPreference(pref)
                            expanded = false
                        },
                    )
                }
            }
        }

        SettingRow(title = "Date format") {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = true }) { Text(state.dateFormat) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf("descriptive", "numeric").forEach { fmt ->
                    DropdownMenuItem(
                        text = { Text(fmt) },
                        onClick = {
                            vm.setDateFormat(fmt)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
