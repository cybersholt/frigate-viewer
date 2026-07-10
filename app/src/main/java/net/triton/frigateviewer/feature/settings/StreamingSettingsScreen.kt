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
fun StreamingSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    SettingsSubPageScaffold(title = "Streaming", onBack = onBack) {
        SwitchSetting(
            title = "Prefer sub stream (Fullscreen)",
            description = "Uses the low-bandwidth sub stream when viewing fullscreen if available.",
            checked = state.preferSubStreamFullscreen,
            onCheckedChange = vm::setPreferSubStreamFullscreen,
        )

        SettingRow(title = "Fullscreen stream option") {
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = true }) { Text(state.fullscreenStreamType) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf("webrtc", "rtsp", "snapshot").forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            vm.setFullscreenStreamType(option)
                            expanded = false
                        },
                    )
                }
            }
        }

        SwitchSetting(
            title = "Hide event image",
            description = "Hide the last event snapshot below the live stream.",
            checked = state.hideEventImageInStream,
            onCheckedChange = vm::setHideEventImageInStream,
        )

        SwitchSetting(
            title = "Auto landscape",
            description = "Go into landscape mode when viewing a stream.",
            checked = state.autoLandscapeOnStream,
            onCheckedChange = vm::setAutoLandscapeOnStream,
        )
    }
}
