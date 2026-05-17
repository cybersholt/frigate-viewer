package net.triton.frigateviewer.feature.events

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun EventsScreen(vm: EventsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.error != null -> Text(
                "Couldn't load events: ${state.error}",
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            state.events.isEmpty() -> Text("No events", Modifier.align(Alignment.Center))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.events, key = { it.id }) { ev ->
                    EventRow(ev)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EventRow(ev: net.triton.frigateviewer.core.model.FrigateEvent) {
    val ts = Instant.fromEpochMilliseconds((ev.startTime * 1000).toLong())
        .toLocalDateTime(TimeZone.currentSystemDefault())
    Box(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(
            "${ev.camera} • ${ev.label} • $ts",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
