package net.triton.frigateviewer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import net.triton.frigateviewer.core.network.ApiResult

private const val AUTO_REFRESH_INTERVAL_MS = 3_000L

@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    var autoRefresh by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { vm.fetchStats() }

    LaunchedEffect(autoRefresh) {
        while (autoRefresh) {
            delay(AUTO_REFRESH_INTERVAL_MS)
            vm.fetchStats()
        }
    }

    SettingsSubPageScaffold(title = "Advanced", onBack = onBack) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("System stats", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(checked = autoRefresh, onCheckedChange = { autoRefresh = it })
                Text("Auto", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { vm.fetchStats() }) { Text("Refresh") }
            }
        }
        when (val result = stats) {
            null -> {
                CircularProgressIndicator()
            }

            is ApiResult.Success -> {
                val root = result.data as? JsonObject
                if (root != null) {
                    SystemStatsOverview(root)
                }
            }

            is ApiResult.HttpError -> {
                Text("HTTP ${result.code}: ${result.message}", color = MaterialTheme.colorScheme.error)
            }

            is ApiResult.NetworkError -> {
                Text(result.cause.message ?: "Network error", color = MaterialTheme.colorScheme.error)
            }

            is ApiResult.ParseError -> {
                Text("Server returned an unexpected response", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
