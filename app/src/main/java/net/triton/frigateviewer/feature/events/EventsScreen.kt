package net.triton.frigateviewer.feature.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dagger.hilt.android.EntryPointAccessors
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.triton.frigateviewer.core.image.FrigateImage
import net.triton.frigateviewer.core.model.FrigateEvent

@Composable
fun EventsScreen(
    onEventClick: (String) -> Unit = {},
    vm: EventsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val imageLoader = remember {
        EntryPointAccessors.fromApplication(context, EventDetailEntryPoint::class.java).imageLoader()
    }
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
                    EventRow(ev, state.baseUrl, imageLoader) { onEventClick(ev.id) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EventRow(
    ev: FrigateEvent,
    baseUrl: String?,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ev.hasSnapshot && baseUrl != null) {
            FrigateImage(
                relativePath = "api/events/${ev.id}/thumbnail.jpg",
                contentDescription = null,
                baseUrl = baseUrl,
                imageLoader = imageLoader,
                modifier = Modifier.size(72.dp, 54.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            val ts = Instant.fromEpochMilliseconds((ev.startTime * 1000).toLong())
                .toLocalDateTime(TimeZone.currentSystemDefault())
            Text("${ev.camera} • ${ev.label}", style = MaterialTheme.typography.bodyMedium)
            Text("$ts", style = MaterialTheme.typography.bodySmall)
        }
        if (ev.retained) Text("★", style = MaterialTheme.typography.titleMedium)
    }
}
