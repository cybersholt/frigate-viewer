package net.triton.frigateviewer.feature.events

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.ImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.image.FrigateImage
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.network.ApiResult
import javax.inject.Inject

@EntryPoint
@InstallIn(SingletonComponent::class)
interface EventDetailEntryPoint {
    fun imageLoader(): ImageLoader
}

data class EventDetailUiState(
    val loading: Boolean = true,
    val event: FrigateEvent? = null,
    val baseUrl: String? = null,
    val error: String? = null,
)

@HiltViewModel
class EventDetailViewModel @Inject constructor(
    private val repo: FrigateRepository,
    private val serverRepo: ServerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EventDetailUiState())
    val state = _state.asStateFlow()

    fun load(id: String) {
        viewModelScope.launch {
            val server = serverRepo.activeServer()
            val baseUrl = server?.baseUrl()
            when (val r = repo.event(id)) {
                is ApiResult.Success -> _state.value = EventDetailUiState(false, r.data, baseUrl)
                is ApiResult.HttpError -> _state.value = EventDetailUiState(false, null, baseUrl, "HTTP ${r.code}")
                is ApiResult.NetworkError -> _state.value = EventDetailUiState(false, null, baseUrl, r.cause.message)
                is ApiResult.ParseError -> _state.value = EventDetailUiState(false, null, baseUrl, "Bad response")
            }
        }
    }
}

@Composable
fun EventDetailScreen(
    eventId: String,
    vm: EventDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val imageLoader = remember {
        EntryPointAccessors.fromApplication(context, EventDetailEntryPoint::class.java).imageLoader()
    }
    LaunchedEffect(eventId) { vm.load(eventId) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        when {
            state.loading -> Text("Loading...")
            state.error != null -> Text("Error: ${state.error}")
            state.event != null -> {
                val ev = state.event!!
                Text("${ev.camera} • ${ev.label}", style = MaterialTheme.typography.titleLarge)
                val ts = Instant.fromEpochMilliseconds((ev.startTime * 1000).toLong())
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                Text("$ts", style = MaterialTheme.typography.bodySmall)
                if (ev.hasSnapshot) {
                    FrigateImage(
                        relativePath = "api/events/${ev.id}/snapshot.jpg?h=720",
                        contentDescription = "snapshot",
                        baseUrl = state.baseUrl,
                        imageLoader = imageLoader,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(top = 12.dp),
                    )
                }
                if (ev.hasClip && state.baseUrl != null) {
                    ClipPlayer(
                        url = state.baseUrl!!.trimEnd('/') + "/api/events/${ev.id}/clip.mp4",
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipPlayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
    )
}

