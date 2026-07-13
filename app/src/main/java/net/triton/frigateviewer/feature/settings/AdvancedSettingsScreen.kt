package net.triton.frigateviewer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import net.triton.frigateviewer.core.network.ApiResult

@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var autoRefresh by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { vm.fetchStats() }

    // Poll rate is user-configurable (Developer Options → Stats poll rate) rather than a hardcoded
    // 3s: this screen is the one place a faster refresh is genuinely useful and a slower one saves
    // battery, and only the person watching it knows which they want.
    val statsPollSeconds = state.statsPollSeconds
    LaunchedEffect(autoRefresh, statsPollSeconds) {
        while (autoRefresh) {
            delay(statsPollSeconds.coerceAtLeast(1) * 1_000L)
            vm.fetchStats()
        }
    }

    SettingsSubPageScaffold(title = "System Metrics", onBack = onBack) {
        SettingsSectionHeader("System stats")

        SwitchSetting(
            title = "Auto-refresh",
            description = "Poll Frigate's stats endpoint every 3 seconds.",
            icon = Icons.Filled.Sync,
            checked = autoRefresh,
            onCheckedChange = { autoRefresh = it },
        )

        ActionSetting(
            title = "Refresh now",
            icon = Icons.Filled.Refresh,
            actionLabel = "Refresh",
            onClick = vm::fetchStats,
        )

        when (val result = stats) {
            null -> {
                SettingsCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        LoadingIndicator()
                    }
                }
            }

            is ApiResult.Success -> {
                val root = result.data as? JsonObject
                if (root != null) {
                    SystemStatsOverview(root)
                }
            }

            is ApiResult.HttpError -> {
                SettingsCard {
                    Text("HTTP ${result.code}: ${result.message}", color = MaterialTheme.colorScheme.error)
                }
            }

            is ApiResult.NetworkError -> {
                SettingsCard {
                    Text(result.cause.message ?: "Network error", color = MaterialTheme.colorScheme.error)
                }
            }

            is ApiResult.ParseError -> {
                SettingsCard {
                    Text("Server returned an unexpected response", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
