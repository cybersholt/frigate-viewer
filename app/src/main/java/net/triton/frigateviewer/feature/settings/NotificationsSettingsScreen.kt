package net.triton.frigateviewer.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DoNotDisturb
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@Composable
fun NotificationsSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    SettingsSubPageScaffold(title = "Notifications", onBack = onBack) {
        SettingsSectionHeader("Push notifications")

        SwitchSetting(
            title = "Enable notifications",
            description = "Get notified when Frigate detects a new event.",
            icon = Icons.Filled.Notifications,
            checked = state.notificationsEnabled,
            onCheckedChange = vm::setNotificationsEnabled,
        )

        SettingsSectionHeader("Filters")

        MultiSelectSettingRow(
            title = "Cameras",
            icon = Icons.Filled.Videocam,
            allOptions = state.knownCameraNames,
            selected = state.notificationCameraFilter,
            onApply = vm::setNotificationCameraFilter,
        )

        TagInputSettingRow(
            title = "Labels",
            icon = Icons.AutoMirrored.Filled.Label,
            description = "Only notify for these object labels (e.g. person, car). Leave empty for all.",
            values = state.notificationLabelFilter,
            onValuesChange = vm::setNotificationLabelFilter,
        )

        TagInputSettingRow(
            title = "Zones",
            icon = Icons.Filled.Place,
            description = "Only notify for events touching these zones. Leave empty for all.",
            values = state.notificationZoneFilter,
            onValuesChange = vm::setNotificationZoneFilter,
        )

        SettingsSectionHeader("Quiet hours")

        SwitchSetting(
            title = "Enable quiet hours",
            description = "Silence notifications during a daily time window.",
            icon = Icons.Filled.DoNotDisturb,
            checked = state.quietHoursEnabled,
            onCheckedChange = vm::setQuietHoursEnabled,
        )

        if (state.quietHoursEnabled) {
            SettingRow(
                title = "Starts at",
                icon = Icons.Filled.Bedtime,
                value = formatMinutesOfDay(state.quietHoursStartMinutes),
                onClick = { showStartPicker = true },
            )
            SettingRow(
                title = "Ends at",
                icon = Icons.Filled.WbSunny,
                value = formatMinutesOfDay(state.quietHoursEndMinutes),
                onClick = { showEndPicker = true },
            )
        }
    }

    if (showStartPicker) {
        MinutesOfDayPickerDialog(
            title = "Quiet hours start",
            initialMinutes = state.quietHoursStartMinutes,
            onConfirm = {
                vm.setQuietHoursStartMinutes(it)
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false },
        )
    }

    if (showEndPicker) {
        MinutesOfDayPickerDialog(
            title = "Quiet hours end",
            initialMinutes = state.quietHoursEndMinutes,
            onConfirm = {
                vm.setQuietHoursEndMinutes(it)
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinutesOfDayPickerDialog(
    title: String,
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState =
        rememberTimePickerState(
            initialHour = (initialMinutes / 60) % 24,
            initialMinute = initialMinutes % 60,
            is24Hour = false,
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatMinutesOfDay(totalMinutes: Int): String {
    val hour24 = (totalMinutes / 60) % 24
    val minute = totalMinutes % 60
    val period = if (hour24 < 12) "AM" else "PM"
    val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
    return String.format(Locale.US, "%d:%02d %s", hour12, minute, period)
}
