package net.triton.frigateviewer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.data.CredentialStore
import net.triton.frigateviewer.core.data.FrigateRepository
import net.triton.frigateviewer.core.data.Server
import net.triton.frigateviewer.core.data.ServerRepository
import net.triton.frigateviewer.core.network.AuthMode
import net.triton.frigateviewer.core.network.FrigateClient
import net.triton.frigateviewer.notification.ServiceController
import java.util.UUID
import javax.inject.Inject

data class ServerForm(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: String = "https",
    val host: String = "",
    val port: String = "8971",
    val basePath: String = "",
    val authMode: AuthMode = AuthMode.NONE,
    val username: String = "",
    val password: String = "",
)

data class SettingsUiState(
    val servers: List<Server> = emptyList(),
    val activeId: String? = null,
    val testResult: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val serverRepo: ServerRepository,
    private val credentialStore: CredentialStore,
    private val repo: FrigateRepository,
    private val client: FrigateClient,
    private val serviceController: ServiceController,
) : ViewModel() {

    val state: kotlinx.coroutines.flow.StateFlow<SettingsUiState> =
        combine(serverRepo.servers, serverRepo.activeServerId) { s, a -> SettingsUiState(s, a) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    @Suppress("unused")
    private val _testResult = MutableStateFlow<String?>(null)

    fun saveServer(form: ServerForm) {
        viewModelScope.launch {
            val port = form.port.toIntOrNull()
            val server = Server(
                id = form.id,
                name = form.name.ifBlank { form.host },
                protocol = form.protocol,
                host = form.host.trim(),
                port = port,
                basePath = form.basePath.trim(),
                authMode = form.authMode,
                username = form.username.ifBlank { null },
            )
            serverRepo.upsert(server)
            client.invalidate(server.id)
            if (form.password.isNotBlank()) {
                val raw = if (form.authMode == AuthMode.BASIC) "${form.username}:${form.password}"
                          else form.password
                credentialStore.setPassword(server.id, raw)
            }
            if (serverRepo.activeServer() == null) serverRepo.setActive(server.id)
            if (form.authMode == AuthMode.FRIGATE) {
                repo.login(server.id, form.username, form.password)
            }
            serviceController.start()
        }
    }

    fun setActive(id: String) {
        viewModelScope.launch {
            serverRepo.setActive(id)
            serviceController.stop()
            serviceController.start()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            serverRepo.delete(id)
            credentialStore.delete(id)
            client.invalidate(id)
        }
    }

    fun logout(id: String) {
        viewModelScope.launch {
            repo.logout(id)
            serviceController.stop()
        }
    }
}
