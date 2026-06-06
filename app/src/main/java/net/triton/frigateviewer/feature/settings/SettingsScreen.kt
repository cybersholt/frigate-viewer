package net.triton.frigateviewer.feature.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaLibraryInfo
import com.materialkolor.PaletteStyle
import net.triton.frigateviewer.core.data.Server
import net.triton.frigateviewer.core.network.AuthMode
import net.triton.frigateviewer.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val currentSsid by vm.currentSsid.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ServerForm?>(null) }
    var showPaletteSheet by remember { mutableStateOf(false) }
    var showShapeSheet by remember { mutableStateOf(false) }
    var showCustomColorPicker by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    Scaffold { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Servers", style = MaterialTheme.typography.titleLarge)
            state.servers.forEach { srv ->
                ServerRow(
                    server = srv,
                    active = srv.id == state.activeId,
                    onMakeActive = { vm.setActive(srv.id) },
                    onDelete = { vm.delete(srv.id) },
                    onLogout = { vm.logout(srv.id) },
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

            HorizontalDivider()
            Text("Appearance", style = MaterialTheme.typography.titleLarge)

            // Theme Mode
            SettingRow(title = "Color mode") {
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = true }) { Text(state.themeMode) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ThemeMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.name) },
                            onClick = {
                                vm.setThemeMode(mode.name)
                                expanded = false
                            },
                        )
                    }
                }
            }

            AccentColorRow(
                currentColor = state.accentColor,
                customColors = state.customAccentColors,
                onSelectColor = { vm.setAccentColor(it) },
                onAddCustom = { showCustomColorPicker = true },
                onRemoveCustom = { vm.removeCustomAccentColor(it) },
            )

            // Wallpaper toggle
            SwitchSetting(
                title = "Use wallpaper color",
                checked = state.useWallpaperColor,
                onCheckedChange = vm::setUseWallpaperColor,
            )

            // Palette Style
            SettingRow(title = "Palette style") {
                TextButton(onClick = { showPaletteSheet = true }) { Text(state.paletteStyle) }
            }

            // AMOLED Black
            SwitchSetting(
                title = "AMOLED black",
                checked = state.amoledBlack,
                onCheckedChange = vm::setAmoledBlack,
            )

            // Contrast
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Contrast", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.contrastLevel >= 0) "+${state.contrastLevel}" else "${state.contrastLevel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = state.contrastLevel.toFloat(),
                    onValueChange = { vm.setContrastLevel(it.toInt()) },
                    valueRange = -100f..100f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Card shape
            SettingRow(title = "Card shape") {
                TextButton(onClick = { showShapeSheet = true }) {
                    Text(cardShapeOptions.find { it.radiusDp == state.cardCornerRadius }?.label ?: "Medium")
                }
            }

            // Border width
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Border width", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.cardBorderWidth == 0) "Off" else "${state.cardBorderWidth}dp",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = state.cardBorderWidth.toFloat(),
                    onValueChange = { vm.setCardBorderWidth(it.toInt()) },
                    valueRange = 0f..8f,
                    steps = 7,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider()
            Text("Cameras View", style = MaterialTheme.typography.titleLarge)

            SwitchSetting(
                title = "Auto-refresh snapshots",
                checked = state.autoRefreshCameras,
                onCheckedChange = vm::setAutoRefreshCameras,
            )

            SettingRow(title = "Refresh interval") {
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = true }) { Text("${state.autoRefreshInterval}s") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf(1, 3, 5, 10, 30).forEach { seconds ->
                        DropdownMenuItem(
                            text = { Text("${seconds}s") },
                            onClick = {
                                vm.setAutoRefreshInterval(seconds)
                                expanded = false
                            },
                        )
                    }
                }
            }

            SettingRow(title = "Grid columns") {
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = true }) { Text("${state.cameraGridColumns}x") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf(1, 2, 3).forEach { cols ->
                        DropdownMenuItem(
                            text = { Text("${cols}x") },
                            onClick = {
                                vm.setCameraGridColumns(cols)
                                expanded = false
                            },
                        )
                    }
                }
            }

            HorizontalDivider()
            Text("Stream Settings", style = MaterialTheme.typography.titleLarge)

            SwitchSetting(
                title = "Prefer sub stream",
                description = "Uses the low-bandwidth sub stream if available.",
                checked = state.preferSubStream,
                onCheckedChange = vm::setPreferSubStream,
            )

            SwitchSetting(
                title = "Hide event image",
                description = "Hide the last event snapshot below the live stream.",
                checked = state.hideEventImageInStream,
                onCheckedChange = vm::setHideEventImageInStream,
            )

            SwitchSetting(
                title = "Auto landscape",
                description = "Go into landscape mode when viewing a stream.",
                checked = state.autoLandscapeOnStream,
                onCheckedChange = vm::setAutoLandscapeOnStream,
            )

            SettingRow(title = "Live stream option") {
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = true }) { Text(state.liveStreamOption) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("webrtc", "rtsp", "snapshot").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                vm.setLiveStreamOption(option)
                                expanded = false
                            },
                        )
                    }
                }
            }

            SwitchSetting(
                title = "Show bounding boxes",
                description = "Overlay detected objects on the live stream.",
                checked = state.showBoundingBoxes,
                onCheckedChange = vm::setShowBoundingBoxes,
            )

            HorizontalDivider()
            Text("Events Settings", style = MaterialTheme.typography.titleLarge)

            SettingRow(title = "Events grid") {
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = true }) { Text("${state.eventGridColumns}x") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf(1, 2, 3).forEach { cols ->
                        DropdownMenuItem(
                            text = { Text("${cols}x") },
                            onClick = {
                                vm.setEventGridColumns(cols)
                                expanded = false
                            },
                        )
                    }
                }
            }

            SettingRow(title = "Photo preference") {
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = true }) { Text(state.eventPhotoPreference) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("snapshot", "thumbnail").forEach { pref ->
                        DropdownMenuItem(
                            text = { Text(pref) },
                            onClick = {
                                vm.setEventPhotoPreference(pref)
                                expanded = false
                            },
                        )
                    }
                }
            }

            SettingRow(title = "Date format") {
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = true }) { Text(state.dateFormat) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("descriptive", "numeric").forEach { fmt ->
                        DropdownMenuItem(
                            text = { Text(fmt) },
                            onClick = {
                                vm.setDateFormat(fmt)
                                expanded = false
                            },
                        )
                    }
                }
            }

            HorizontalDivider()
            Text("Device Capabilities", style = MaterialTheme.typography.titleLarge)
            DeviceCapabilitiesSection()
        }
    }

    editing?.let { form ->
        ServerFormSheet(
            form = form,
            currentSsid = currentSsid,
            onDismiss = { editing = null },
            onSave = {
                vm.saveServer(it)
                editing = null
            },
        )
    }

    if (showPaletteSheet) {
        PaletteStyleSheet(
            current = state.paletteStyle,
            onSelect = vm::setPaletteStyle,
            onDismiss = { showPaletteSheet = false },
        )
    }

    if (showShapeSheet) {
        CardShapeSheet(
            current = state.cardCornerRadius,
            onSelect = vm::setCardCornerRadius,
            onDismiss = { showShapeSheet = false },
        )
    }

    if (showCustomColorPicker) {
        AddCustomColorSheet(
            onAdd = vm::addCustomAccentColor,
            onDismiss = { showCustomColorPicker = false },
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // Box provides the anchor for any DropdownMenu inside content() so the popup
        // positions relative to the button rather than the left edge of the Row.
        Box { content() }
    }
}

@Composable
private fun SwitchSetting(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ServerRow(
    server: Server,
    active: Boolean,
    onMakeActive: () -> Unit,
    onEdit: () -> Unit,
    onLogout: () -> Unit,
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
                TextButton(onClick = onLogout) { Text("Logout") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerFormSheet(
    form: ServerForm,
    currentSsid: String?,
    onDismiss: () -> Unit,
    onSave: (ServerForm) -> Unit,
) {
    var current by remember { mutableStateOf(form.copy(protocol = "https", port = "")) }
    var newSsidInput by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val context = LocalContext.current

    val locationPermLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted && currentSsid != null && currentSsid !in current.localNetworkSsids) {
                current = current.copy(localNetworkSsids = current.localNetworkSsids + currentSsid)
            }
        }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
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
                if (current.authMode != AuthMode.NONE) {
                    OutlinedTextField(
                        current.username,
                        { current = current.copy(username = it) },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        current.password,
                        { current = current.copy(password = it) },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
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

                if (currentSsid != null) {
                    TextButton(
                        onClick = {
                            val hasPermission =
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                ) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                if (currentSsid !in current.localNetworkSsids) {
                                    current = current.copy(localNetworkSsids = current.localNetworkSsids + currentSsid)
                                }
                            } else {
                                locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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
                }
            }
        }
    }
}

@Composable
private fun AuthModeSelector(
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

@Suppress("NewApi")
@Composable
private fun DeviceCapabilitiesSection() {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val sampleRate =
        remember {
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        }
    val framesPerBuffer =
        remember {
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        }
    val isLowLatency =
        remember {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        }
    val isProAudio =
        remember {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)
        }
    val maxMemMb = remember { Runtime.getRuntime().maxMemory() / 1024 / 1024 }
    val freeMemMb = remember { Runtime.getRuntime().freeMemory() / 1024 / 1024 }
    val outputDevices = remember { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
    val h265ok =
        remember {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec ->
                !codec.isEncoder && codec.supportedTypes.any { it.contains("hevc", ignoreCase = true) }
            }
        }

    val capabilityItems =
        listOf(
            "Sample Rate" to "${sampleRate / 1000} kHz ($framesPerBuffer f/buf)",
            "Outputs" to "${outputDevices.size} detected",
            "Low Latency" to if (isLowLatency) "Yes (Pro Audio: $isProAudio)" else "No",
            "JVM Memory" to "${freeMemMb}MB free / ${maxMemMb}MB max",
        )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        capabilityItems.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text("Detected outputs", style = MaterialTheme.typography.labelLarge)
    outputDevices.forEach { device ->
        ListItem(
            headlineContent = { Text(device.productName.toString()) },
            supportingContent = { Text(device.typeLabel()) },
            leadingContent = {
                Icon(
                    if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                        Icons.Filled.Headphones
                    } else {
                        Icons.Filled.Speaker
                    },
                    contentDescription = null,
                )
            },
        )
    }

    Spacer(Modifier.height(8.dp))
    Text("Media3 ${MediaLibraryInfo.VERSION}", style = MaterialTheme.typography.labelMedium)
    Text(
        "H264: supported | H265: ${if (h265ok) "supported" else "not supported"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun AudioDeviceInfo.typeLabel() =
    when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "Other"
    }
