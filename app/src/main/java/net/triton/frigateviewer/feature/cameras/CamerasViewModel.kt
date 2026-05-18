package net.triton.frigateviewer.feature.cameras

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.Server
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.model.CameraConfig
import net.triton.frigateviewer.core.network.ApiResult
import javax.inject.Inject

data class CamerasUiState(
    val loading: Boolean = false,
    val cameras: Map<String, CameraConfig> = emptyMap(),
    val errorMessage: String? = null,
    val noServerConfigured: Boolean = false,
    val activeServer: Server? = null,
)

@HiltViewModel
class CamerasViewModel @Inject constructor(
    private val repo: FrigateRepository,
    private val serverRepo: ServerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CamerasUiState())
    val state: StateFlow<CamerasUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val server = serverRepo.activeServer()
            if (server == null) {
                _state.value = CamerasUiState(noServerConfigured = true)
                return@launch
            }
            _state.value = _state.value.copy(loading = true, errorMessage = null, activeServer = server)
            when (val r = repo.config()) {
                is ApiResult.Success -> _state.value = CamerasUiState(cameras = r.data.cameras, activeServer = server)
                is ApiResult.HttpError -> _state.value = CamerasUiState(
                    errorMessage = "HTTP ${r.code}: ${r.message}",
                    activeServer = server,
                )
                is ApiResult.NetworkError -> _state.value = CamerasUiState(
                    errorMessage = "Network error: ${r.cause.message ?: "connection failed"}",
                    activeServer = server,
                )
                is ApiResult.ParseError -> _state.value = CamerasUiState(
                    errorMessage = "Server returned an unexpected response. Check URL + auth.",
                    activeServer = server,
                )
            }
        }
    }
}
