package net.triton.frigateviewer.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.triton.frigateviewer.core.data.Server
import net.triton.frigateviewer.core.network.AuthMode

@Composable
fun ServersSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val currentSsid by vm.currentSsid.collectAsStateWithLifecycle()
    val connectionTest by vm.connectionTest.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ServerForm?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }

    SettingsSubPageScaffold(title = "Servers", onBack = onBack) {
        state.servers.forEach { srv ->
            ServerRow(
                server = srv,
                active = srv.id == state.activeId,
                onMakeActive = { vm.setActive(srv.id) },
                onDelete = { vm.delete(srv.id) },
                onLogout = { vm.logout(srv.id) },
                onTest = {
                    testingId = srv.id
                    vm.testConnection(srv.id)
                },
                onEdit = {
                    editing =
                        ServerForm(
                            id = srv.id,
                            name = srv.name,
                            protocol = srv.protocol,
                            host = srv.host,
                            port = srv.port?.toString().orEmpty(),
                            basePath = srv.basePath,
                            authMode = srv.authMode,
                            username = srv.username.orEmpty(),
                            allowUntrusted = srv.allowUntrusted,
                            rtspPort = srv.rtspPort.toString(),
                            rtspHost = srv.rtspHost.orEmpty(),
                            localNetworkUrl = srv.localNetworkUrl.orEmpty(),
                            localNetworkSsids = srv.localNetworkSsids,
                        )
                },
            )
        }
        Button(onClick = { editing = ServerForm() }) { Text("Add server") }
    }

    editing?.let { form ->
        ServerFormSheet(
            form = form,
            currentSsid = currentSsid,
            connectionTest = connectionTest,
            onTest = { draft -> vm.testDraftConnection(draft) },
            onClearTest = { vm.clearConnectionTest() },
            onPermissionGranted = { vm.refreshWifiSsid() },
            onDismiss = { editing = null },
            onSave = {
                vm.saveServer(it)
                editing = null
            },
        )
    }

    if (testingId != null && connectionTest != null && connectionTest?.serverId == testingId) {
        ConnectionTestDialog(
            result = connectionTest!!,
            onDismiss = {
                testingId = null
                vm.clearConnectionTest()
            },
        )
    } else if (testingId != null) {
        // Waiting for the in-flight test to complete.
        AlertDialog(
            onDismissRequest = { testingId = null },
            confirmButton = {},
            title = { Text("Testing connection…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("Contacting server…")
                }
            },
        )
    }
}

@Composable
private fun ConnectionTestDialog(
    result: ConnectionTestResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        icon = {
            Icon(
                if (result.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = if (result.success) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(if (result.success) "Server reachable" else "Connection failed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(result.message)
                if (result.latencyMs != null) {
                    Text(
                        "Round-trip: ${result.latencyMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
internal fun ServerRow(
    server: Server,
    active: Boolean,
    onMakeActive: () -> Unit,
    onEdit: () -> Unit,
    onLogout: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
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
                TextButton(onClick = onTest) { Text("Test") }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onLogout) { Text("Logout") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerFormSheet(
    form: ServerForm,
    currentSsid: String?,
    connectionTest: ConnectionTestResult?,
    onTest: (ServerForm) -> Unit,
    onClearTest: () -> Unit,
    onPermissionGranted: () -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (ServerForm) -> Unit,
) {
    var current by remember { mutableStateOf(form) }
    var newSsidInput by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val context = LocalContext.current
    var hasLocationPermission by
        remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED,
            )
        }

    // Granting the permission doesn't retroactively fix an already-null currentSsid on its own
    // (see WifiMonitor.refresh doc) — onPermissionGranted asks the ViewModel to force a fresh read.
    val locationPermLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasLocationPermission = granted
            if (granted) onPermissionGranted()
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                OutlinedButton(
                    onClick = {
                        testing = true
                        onTest(current)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Test") }
                Button(onClick = { onSave(current) }, modifier = Modifier.weight(1f)) { Text("Save") }
            }
            HorizontalDivider()
            Column(
                Modifier.fillMaxWidth().verticalScroll(scroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    current.name,
                    { current = current.copy(name = it) },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    current.host,
                    { current = current.copy(host = it) },
                    label = { Text("Host (use host:port for non-443)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    current.rtspPort,
                    { current = current.copy(rtspPort = it) },
                    label = { Text("RTSP Port") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    current.rtspHost,
                    { current = current.copy(rtspHost = it) },
                    label = { Text("RTSP host (optional — LAN IP if domain can't reach port 8554)") },
                    placeholder = { Text(current.host.ifBlank { "e.g. 192.168.1.10" }) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    current.basePath,
                    { current = current.copy(basePath = it) },
                    label = { Text("Base path (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                AuthModeSelector(current.authMode) { current = current.copy(authMode = it) }
                SwitchSetting(
                    title = "Allow untrusted certs",
                    description = "Allow self-signed certificates for this server.",
                    checked = current.allowUntrusted,
                    onCheckedChange = { current = current.copy(allowUntrusted = it) },
                )
                SwitchSetting(
                    title = "Use SSL (HTTPS)",
                    description = "Disable to use HTTP for local testing.",
                    checked = current.protocol == "https",
                    onCheckedChange = { current = current.copy(protocol = if (it) "https" else "http") },
                )
                if (current.authMode != AuthMode.NONE) {
                    OutlinedTextField(
                        current.username,
                        { current = current.copy(username = it) },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    var passwordVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        current.password,
                        { current = current.copy(password = it) },
                        label = { Text("Password") },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider()
                Text("Local Network", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = current.localNetworkUrl,
                    onValueChange = { current = current.copy(localNetworkUrl = it) },
                    label = { Text("Local Server URL (optional)") },
                    placeholder = { Text("e.g. http://192.168.1.100:5000") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text(
                    "The local server will be used when the current network matches any below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                current.localNetworkSsids.forEach { ssid ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ssid, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = {
                            current = current.copy(localNetworkSsids = current.localNetworkSsids - ssid)
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove")
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newSsidInput,
                        onValueChange = { newSsidInput = it },
                        label = { Text("Network name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val trimmed = newSsidInput.trim()
                            if (trimmed.isNotBlank() && trimmed !in current.localNetworkSsids) {
                                current = current.copy(localNetworkSsids = current.localNetworkSsids + trimmed)
                            }
                            newSsidInput = ""
                        },
                    ) { Text("Add") }
                }

                // Was previously gated behind `currentSsid != null` — a dead end, since Android
                // only reports the real SSID once ACCESS_FINE_LOCATION is granted, and that grant
                // can only happen by tapping a button that this same condition was hiding. Always
                // show a path forward instead: request permission first, then reveal Add/Current-SSID.
                if (!hasLocationPermission) {
                    Text(
                        "Grant location access so Android can report your Wi-Fi network name " +
                            "(required for local-network switching).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                        Text("Grant location access")
                    }
                } else if (currentSsid != null) {
                    TextButton(
                        onClick = {
                            if (currentSsid !in current.localNetworkSsids) {
                                current = current.copy(localNetworkSsids = current.localNetworkSsids + currentSsid)
                            }
                        },
                        modifier = Modifier.padding(top = 0.dp),
                    ) {
                        Text("Add current Wi-Fi")
                    }
                    Text(
                        "Current Wi-Fi: $currentSsid",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Not currently connected to Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (testing && connectionTest != null && connectionTest.serverId == current.id) {
        ConnectionTestDialog(
            result = connectionTest,
            onDismiss = {
                testing = false
                onClearTest()
            },
        )
    } else if (testing) {
        // Waiting for the in-flight draft test to complete.
        AlertDialog(
            onDismissRequest = { testing = false },
            confirmButton = {},
            title = { Text("Testing connection…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("Contacting server…")
                }
            },
        )
    }
}

@Composable
internal fun AuthModeSelector(
    current: AuthMode,
    onChange: (AuthMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Auth: ${current.name.lowercase()}", modifier = Modifier.weight(1f))
        TextButton(onClick = { expanded = true }) { Text("Change") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AuthMode.entries.forEach { mode ->
                DropdownMenuItem(text = { Text(mode.name.lowercase()) }, onClick = {
                    onChange(mode)
                    expanded = false
                })
            }
        }
    }
}
