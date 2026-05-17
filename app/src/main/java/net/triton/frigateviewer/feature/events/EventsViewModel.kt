package net.triton.frigateviewer.feature.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.model.FrigateEvent
import net.triton.frigateviewer.core.network.ApiResult
import javax.inject.Inject

data class EventsUiState(
    val loading: Boolean = false,
    val events: List<FrigateEvent> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class EventsViewModel @Inject constructor(
    private val repo: FrigateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EventsUiState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val r = repo.events(limit = 100)) {
                is ApiResult.Success -> _state.value = EventsUiState(events = r.data)
                is ApiResult.HttpError -> _state.value = EventsUiState(error = "HTTP ${r.code}")
                is ApiResult.NetworkError -> _state.value = EventsUiState(error = r.cause.message ?: "Network error")
                is ApiResult.ParseError -> _state.value = EventsUiState(error = "Bad response from server")
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            when (repo.deleteEvent(id)) {
                is ApiResult.Success -> _state.value = _state.value.copy(events = _state.value.events.filterNot { it.id == id })
                else -> { /* surface via snackbar in v0.2 */ }
            }
        }
    }
}
