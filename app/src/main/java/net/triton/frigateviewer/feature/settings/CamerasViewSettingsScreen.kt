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
fun CamerasViewSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    SettingsSubPageScaffold(title = "Cameras View", onBack = onBack) {
        SwitchSetting(
            title = "Auto-refresh snapshots",
            checked = state.autoRefreshCameras,
            onCheckedChange = vm::setAutoRefreshCameras,
        )

        SettingRow(title = "Refresh interval") {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = true }) { Text("${state.autoRefreshInterval}s") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf(1, 3, 5, 10, 30).forEach { seconds ->
                    DropdownMenuItem(
                        text = { Text("${seconds}s") },
                        onClick = {
                            vm.setAutoRefreshInterval(seconds)
                            expanded = false
                        },
                    )
                }
            }
        }

        SettingRow(title = "Grid stream type") {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = true }) { Text(state.gridStreamType) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf("snapshot", "webrtc", "rtsp").forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type) },
                        onClick = {
                            vm.setGridStreamType(type)
                            expanded = false
                        },
                    )
                }
            }
        }

        SwitchSetting(
            title = "Prefer sub stream (Grid)",
            description = "Uses the low-bandwidth sub stream in the grid if available.",
            checked = state.preferSubStreamGrid,
            onCheckedChange = vm::setPreferSubStreamGrid,
        )

        SwitchSetting(
            title = "Keep offscreen tiles alive",
            description = "Maintains active connections to cameras even when scrolled out of view.",
            checked = state.keepOffscreenTilesAlive,
            onCheckedChange = vm::setKeepOffscreenTilesAlive,
        )

        SettingRow(title = "Grid columns") {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = true }) { Text("${state.cameraGridColumns}x") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf(1, 2, 3).forEach { cols ->
                    DropdownMenuItem(
                        text = { Text("${cols}x") },
                        onClick = {
                            vm.setCameraGridColumns(cols)
                            expanded = false
                        },
                    )
                }
            }
        }

        SwitchSetting(
            title = "Show bounding boxes",
            description = "Overlay detected objects on the live stream.",
            checked = state.showBoundingBoxes,
            onCheckedChange = vm::setShowBoundingBoxes,
        )

        SwitchSetting(
            title = "Show last image while loading",
            description = "Display the cached snapshot behind the spinner while a live stream connects.",
            checked = state.showLastImageWhileLoading,
            onCheckedChange = vm::setShowLastImageWhileLoading,
        )
    }
}
