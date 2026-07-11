package net.triton.frigateviewer.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.runtime.Composable

/**
 * "Trigger test crash" deliberately throws on the main thread so the rest of the crash-report
 * pipeline (CrashHandler -> CrashReportStore -> CrashReportScreen on next cold launch) can be
 * exercised end-to-end without waiting for a real bug.
 */
@Composable
fun DeveloperOptionsSettingsScreen(onBack: () -> Unit) {
    SettingsSubPageScaffold(title = "Developer Options", onBack = onBack) {
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
