package net.triton.frigateviewer.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun StreamingSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    SettingsSubPageScaffold(title = "Streaming", onBack = onBack) {
        SettingsSectionHeader("Playback")

        SwitchSetting(
            title = "Prefer sub stream (Fullscreen)",
            description = "Uses the low-bandwidth sub stream when viewing fullscreen if available.",
            icon = Icons.Filled.Speed,
            checked = state.preferSubStreamFullscreen,
            onCheckedChange = vm::setPreferSubStreamFullscreen,
        )

        PickerSettingRow(
            title = "Fullscreen stream option",
            icon = Icons.Filled.Fullscreen,
            value = state.fullscreenStreamType,
            options = listOf("webrtc", "rtsp", "snapshot"),
            onSelect = vm::setFullscreenStreamType,
        )

        SwitchSetting(
            title = "Hide event image",
            description = "Hide the last event snapshot below the live stream.",
            icon = Icons.Filled.HideImage,
            checked = state.hideEventImageInStream,
            onCheckedChange = vm::setHideEventImageInStream,
        )

        SwitchSetting(
            title = "Auto landscape",
            description = "Go into landscape mode when viewing a stream.",
            icon = Icons.Filled.ScreenRotation,
            checked = state.autoLandscapeOnStream,
            onCheckedChange = vm::setAutoLandscapeOnStream,
        )

        SettingsSectionHeader("RTSP Resilience")

        PickerSettingRow(
            title = "Reconnect attempts",
            icon = Icons.Filled.Replay,
            value = state.rtspReconnectAttempts,
            options = (0..6).toList(),
            labelFor = { if (it == 0) "Off" else "$it" },
            onSelect = vm::setRtspReconnectAttempts,
        )

        PickerSettingRow(
            title = "Time between attempts",
            icon = Icons.Filled.Timer,
            value = state.rtspReconnectBaseDelaySeconds,
            options = listOf(1, 2, 3, 5, 10, 15),
            labelFor = { "${it}s (doubles each retry)" },
            onSelect = vm::setRtspReconnectBaseDelaySeconds,
        )
    }
}
