package net.triton.frigateviewer.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@Composable
fun CamerasViewSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    SettingsSubPageScaffold(title = "Cameras View", onBack = onBack) {
        SettingsSectionHeader("Refresh")

        SwitchSetting(
            title = "Auto-refresh snapshots",
            icon = Icons.Filled.Refresh,
            checked = state.autoRefreshCameras,
            onCheckedChange = vm::setAutoRefreshCameras,
        )

        PickerSettingRow(
            title = "Refresh interval",
            icon = Icons.Filled.Timer,
            value = state.autoRefreshInterval,
            options = listOf(1, 3, 5, 10, 30),
            labelFor = { "${it}s" },
            onSelect = vm::setAutoRefreshInterval,
        )

        SettingsSectionHeader("Stream")

        PickerSettingRow(
            title = "Grid stream type",
            icon = Icons.Filled.Videocam,
            value = state.gridStreamType,
            options = listOf("snapshot", "webrtc", "rtsp"),
            onSelect = vm::setGridStreamType,
        )

        SwitchSetting(
            title = "Prefer sub stream (Grid)",
            description = "Uses the low-bandwidth sub stream in the grid if available.",
            icon = Icons.Filled.Speed,
            checked = state.preferSubStreamGrid,
            onCheckedChange = vm::setPreferSubStreamGrid,
        )

        SwitchSetting(
            title = "Keep offscreen tiles alive",
            description = "Maintains active connections to cameras even when scrolled out of view.",
            icon = Icons.Filled.SyncAlt,
            checked = state.keepOffscreenTilesAlive,
            onCheckedChange = vm::setKeepOffscreenTilesAlive,
        )

        SettingsSectionHeader("Tile style")

        PickerSettingRow(
            title = "Surface tint",
            icon = Icons.Filled.Palette,
            value = state.cameraTileTint,
            options = listOf("none", "surface", "primary", "secondary", "tertiary"),
            labelFor = { option -> option.replaceFirstChar { it.uppercase(Locale.US) } },
            onSelect = vm::setCameraTileTint,
        )

        PickerSettingRow(
            title = "Shadow",
            icon = Icons.Filled.Layers,
            value = state.cameraTileShadow,
            options = listOf(0, 2, 4, 8, 12),
            labelFor = { if (it == 0) "None" else "${it}dp" },
            onSelect = vm::setCameraTileShadow,
        )

        SettingsSectionHeader("Layout")

        PickerSettingRow(
            title = "Grid columns",
            icon = Icons.Filled.ViewModule,
            value = state.cameraGridColumns,
            options = listOf(1, 2, 3),
            labelFor = { "${it}x" },
            onSelect = vm::setCameraGridColumns,
        )

        SwitchSetting(
            title = "Show bounding boxes",
            description = "Overlay detected objects on the live stream.",
            icon = Icons.Filled.CropFree,
            checked = state.showBoundingBoxes,
            onCheckedChange = vm::setShowBoundingBoxes,
        )

        SwitchSetting(
            title = "Show last image while loading",
            description = "Display the cached snapshot behind the spinner while a live stream connects.",
            icon = Icons.Filled.Image,
            checked = state.showLastImageWhileLoading,
            onCheckedChange = vm::setShowLastImageWhileLoading,
        )
    }
}
