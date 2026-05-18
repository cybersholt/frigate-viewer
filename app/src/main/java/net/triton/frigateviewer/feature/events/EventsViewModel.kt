package net.triton.frigateviewer.feature.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.network.ApiResult
import javax.inject.Inject

data class EventsUiState(
    val loading: Boolean = false,
    val events: List<FrigateEvent> = emptyList(),
    val error: String? = null,
    val baseUrl: String? = null,
)

@HiltViewModel
class EventsViewModel @Inject constructor(
    private val repo: FrigateRepository,
    private val serverRepo: ServerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EventsUiState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val server = serverRepo.activeServer()
            _state.value = _state.value.copy(loading = true, error = null, baseUrl = server?.baseUrl())
            when (val r = repo.events(limit = 100)) {
                is ApiResult.Success -> _state.value = EventsUiState(events = r.data, baseUrl = server?.baseUrl())
                is ApiResult.HttpError -> _state.value = EventsUiState(error = "HTTP ${r.code}", baseUrl = server?.baseUrl())
                is ApiResult.NetworkError -> _state.value = EventsUiState(error = r.cause.message ?: "Network error", baseUrl = server?.baseUrl())
                is ApiResult.ParseError -> _state.value = EventsUiState(error = "Bad response from server", baseUrl = server?.baseUrl())
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            when (repo.deleteEvent(id)) {
                is ApiResult.Success -> _state.value = _state.value.copy(
                    events = _state.value.events.filterNot { it.id == id }
                )
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
