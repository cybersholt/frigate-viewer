package net.triton.frigateviewer.feature.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.db.CachedEvent
import net.triton.frigateviewer.core.db.EventDao
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.network.ApiResult
import javax.inject.Inject

data class EventsUiState(
    val loading: Boolean = false,
    val events: List<FrigateEvent> = emptyList(),
    val error: String? = null,
    val baseUrl: String? = null,
    val offlineCache: Boolean = false,
)

@HiltViewModel
class EventsViewModel @Inject constructor(
    private val repo: FrigateRepository,
    private val serverRepo: ServerRepository,
    private val dao: EventDao,
) : ViewModel() {

    private val _state = MutableStateFlow(EventsUiState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val server = serverRepo.activeServer()
            val baseUrl = server?.baseUrl()
            // Show cached events instantly while network refresh runs.
            if (server != null) {
                val cached = dao.list(server.id).map { it.toDomain() }
                if (cached.isNotEmpty()) {
                    _state.value = EventsUiState(events = cached, baseUrl = baseUrl, offlineCache = true)
                }
            }
            _state.value = _state.value.copy(loading = true, error = null, baseUrl = baseUrl)
            when (val r = repo.events(limit = 100)) {
                is ApiResult.Success -> {
                    _state.value = EventsUiState(events = r.data, baseUrl = baseUrl)
                    if (server != null) dao.upsertAll(r.data.map { it.toCached(server.id) })
                }
                is ApiResult.HttpError -> _state.value = _state.value.copy(loading = false, error = "HTTP ${r.code}")
                is ApiResult.NetworkError -> _state.value = _state.value.copy(loading = false, error = r.cause.message ?: "Network error")
                is ApiResult.ParseError -> _state.value = _state.value.copy(loading = false, error = "Bad response from server")
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            when (repo.deleteEvent(id)) {
                is ApiResult.Success -> {
                    val server = serverRepo.activeServer()
                    if (server != null) dao.delete(server.id, id)
                    _state.value = _state.value.copy(
                        events = _state.value.events.filterNot { it.id == id }
                    )
                }
                else -> {}
            }
        }
    }

    fun toggleRetain(ev: FrigateEvent) {
        viewModelScope.launch {
            when (repo.retainEvent(ev.id, retain = !ev.retained)) {
                is ApiResult.Success -> _state.value = _state.value.copy(
                    events = _state.value.events.map {
                        if (it.id == ev.id) it.copy(retained = !it.retained) else it
                    }
                )
                else -> {}
            }
        }
    }
}

private fun CachedEvent.toDomain() = FrigateEvent(
    id = eventId,
    camera = camera,
    label = label,
    startTime = startTime,
    endTime = endTime,
    hasSnapshot = hasSnapshot,
    hasClip = hasClip,
    topScore = topScore,
    retained = retained,
)

private fun FrigateEvent.toCached(serverId: String) = CachedEvent(
    serverId = serverId,
    eventId = id,
    camera = camera,
    label = label,
    startTime = startTime,
    endTime = endTime,
    hasSnapshot = hasSnapshot,
    hasClip = hasClip,
    retained = retained,
    topScore = topScore,
)
