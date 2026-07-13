package net.triton.frigateviewer.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * "Trigger test crash" deliberately throws on the main thread so the rest of the crash-report
 * pipeline (CrashHandler -> CrashReportStore -> CrashReportScreen on next cold launch) can be
 * exercised end-to-end without waiting for a real bug.
 */
@Composable
fun DeveloperOptionsSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    SettingsSubPageScaffold(title = "Developer Options", onBack = onBack) {
        SettingsSectionHeader("Diagnostics")
        SwitchSetting(
            title = "Show stream stats",
            description =
                "Overlay live tiles with stream type, bandwidth, latency, frame counts, drop rate " +
                    "and recent-bandwidth charts. Sampled once a second while a tile is on screen.",
            icon = Icons.Filled.QueryStats,
            checked = state.showStreamStats,
            onCheckedChange = vm::setShowStreamStats,
        )

        PickerSettingRow(
            title = "Stats poll rate",
            icon = Icons.Filled.Timer,
            value = state.statsPollSeconds,
            options = listOf(1, 2, 5, 10),
            labelFor = { "${it}s" },
            onSelect = vm::setStatsPollSeconds,
        )

        SettingsSectionHeader("Crash testing")
        ActionSetting(
            title = "Trigger test crash",
            description = "Deliberately crashes the app to exercise the crash-report screen shown on the next launch.",
            icon = Icons.Filled.BugReport,
            actionLabel = "Crash",
            isDestructive = true,
            onClick = { error("Test crash triggered from Developer Options") },
        )
    }
}
