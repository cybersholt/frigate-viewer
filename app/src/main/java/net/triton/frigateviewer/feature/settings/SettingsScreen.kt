package net.triton.frigateviewer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.triton.frigateviewer.core.data.Server
import net.triton.frigateviewer.core.network.AuthMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ServerForm?>(null) }

    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Servers", style = MaterialTheme.typography.titleLarge)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(state.servers, key = { it.id }) { srv ->
                    ServerRow(
                        server = srv,
                        active = srv.id == state.activeId,
                        onMakeActive = { vm.setActive(srv.id) },
                        onDelete = { vm.delete(srv.id) },
                        onEdit = {
                            editing = ServerForm(
                                id = srv.id,
                                name = srv.name,
                                protocol = srv.protocol,
                                host = srv.host,
                                port = srv.port?.toString().orEmpty(),
                                basePath = srv.basePath,
                                authMode = srv.authMode,
                                username = srv.username.orEmpty(),
                            )
                        },
                    )
                }
            }
            Button(onClick = { editing = ServerForm() }) { Text("Add server") }
        }
    }

    editing?.let { form ->
        ServerFormSheet(
            form = form,
            onDismiss = { editing = null },
            onSave = {
                vm.saveServer(it)
                editing = null
            },
        )
    }
}

@Composable
private fun ServerRow(
    server: Server,
    active: Boolean,
    onMakeActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = server.name + if (active) "  (active)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(server.baseUrl(), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (!active) TextButton(onClick = onMakeActive) { Text("Make active") }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerFormSheet(
    form: ServerForm,
    onDismiss: () -> Unit,
    onSave: (ServerForm) -> Unit,
) {
    var current by remember { mutableStateOf(form) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(current.name, { current = current.copy(name = it) }, label = { Text("Name") })
            OutlinedTextField(current.protocol, { current = current.copy(protocol = it) }, label = { Text("Protocol (http/https)") })
            OutlinedTextField(current.host, { current = current.copy(host = it) }, label = { Text("Host") })
            OutlinedTextField(current.port, { current = current.copy(port = it) }, label = { Text("Port") })
            OutlinedTextField(current.basePath, { current = current.copy(basePath = it) }, label = { Text("Base path (optional)") })
            AuthModeSelector(current.authMode) { current = current.copy(authMode = it) }
            if (current.authMode != AuthMode.NONE) {
                OutlinedTextField(current.username, { current = current.copy(username = it) }, label = { Text("Username") })
                OutlinedTextField(
                    current.password,
                    { current = current.copy(password = it) },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = { onSave(current) }, modifier = Modifier.weight(1f)) { Text("Save") }
            }
        }
    }
}

@Composable
private fun AuthModeSelector(current: AuthMode, onChange: (AuthMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Auth: ${current.name.lowercase()}", modifier = Modifier.weight(1f))
        TextButton(onClick = { expanded = true }) { Text("Change") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AuthMode.entries.forEach { mode ->
                DropdownMenuItem(text = { Text(mode.name.lowercase()) }, onClick = {
                    onChange(mode); expanded = false
                })
            }
        }
    }
}
