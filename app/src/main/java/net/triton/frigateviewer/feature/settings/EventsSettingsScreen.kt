package net.triton.frigateviewer.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun EventsSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    SettingsSubPageScaffold(title = "Events", onBack = onBack) {
        PickerSettingRow(
            title = "Events grid",
            icon = Icons.Filled.ViewModule,
            value = state.eventGridColumns,
            options = listOf(1, 2, 3),
            labelFor = { "${it}x" },
            onSelect = vm::setEventGridColumns,
        )

        PickerSettingRow(
            title = "Photo preference",
            icon = Icons.Filled.PhotoCamera,
            value = state.eventPhotoPreference,
            options = listOf("snapshot", "thumbnail"),
            onSelect = vm::setEventPhotoPreference,
        )

        PickerSettingRow(
            title = "Date format",
            icon = Icons.Filled.CalendarToday,
            value = state.dateFormat,
            options = listOf("descriptive", "numeric"),
            onSelect = vm::setDateFormat,
        )
    }
}
