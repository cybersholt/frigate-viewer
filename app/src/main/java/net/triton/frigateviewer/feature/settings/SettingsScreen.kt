package net.triton.frigateviewer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Route strings for the Settings nav graph — single source of truth, referenced from MainActivity's NavHost. */
object SettingsRoutes {
    const val ROOT = "settings"
    const val SERVERS = "settings/servers"
    const val APPEARANCE = "settings/appearance"
    const val CAMERAS_VIEW = "settings/cameras_view"
    const val STREAMING = "settings/streaming"
    const val EVENTS = "settings/events"
    const val NOTIFICATIONS = "settings/notifications"
    const val DOWNLOADS = "settings/downloads"
    const val BACKUP = "settings/backup"
    const val ADVANCED = "settings/advanced"
    const val DEVICE_CAPABILITIES = "settings/device_capabilities"
    const val DEVELOPER_OPTIONS = "settings/developer_options"
    const val ABOUT = "settings/about"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val activeServerName = state.servers.find { it.id == state.activeId }?.name

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(title = { Text("Settings") }, scrollBehavior = scrollBehavior)
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val cs = MaterialTheme.colorScheme
            SettingsNavRow(
                icon = Icons.Filled.Dns,
                iconTint = cs.onPrimaryContainer,
                iconContainerColor = cs.primaryContainer,
                title = "Servers",
                subtitle = activeServerName ?: "No server configured",
                onClick = { onNavigate(SettingsRoutes.SERVERS) },
            )
            SettingsNavRow(
                icon = Icons.Filled.Palette,
                iconTint = cs.onSecondaryContainer,
                iconContainerColor = cs.secondaryContainer,
                title = "Appearance",
                subtitle = "Theme, color, shape",
                onClick = { onNavigate(SettingsRoutes.APPEARANCE) },
            )
            SettingsNavRow(
                icon = Icons.Filled.GridView,
                iconTint = cs.onTertiaryContainer,
                iconContainerColor = cs.tertiaryContainer,
                title = "Cameras View",
                subtitle = "Grid layout, refresh, bounding boxes",
                onClick = { onNavigate(SettingsRoutes.CAMERAS_VIEW) },
            )
            SettingsNavRow(
                icon = Icons.Filled.PlayCircle,
                iconTint = cs.onPrimaryContainer,
                iconContainerColor = cs.primaryContainer,
                title = "Streaming",
                subtitle = "Live stream type, sub stream",
                onClick = { onNavigate(SettingsRoutes.STREAMING) },
            )
            SettingsNavRow(
                icon = Icons.AutoMirrored.Filled.EventNote,
                iconTint = cs.onSecondaryContainer,
                iconContainerColor = cs.secondaryContainer,
                title = "Events",
                subtitle = "Grid, photo preference, date format",
                onClick = { onNavigate(SettingsRoutes.EVENTS) },
            )
            SettingsNavRow(
                icon = Icons.Filled.Notifications,
                iconTint = cs.onTertiaryContainer,
                iconContainerColor = cs.tertiaryContainer,
                title = "Notifications",
                onClick = { onNavigate(SettingsRoutes.NOTIFICATIONS) },
            )
            SettingsNavRow(
                icon = Icons.Filled.Download,
                iconTint = cs.onPrimaryContainer,
                iconContainerColor = cs.primaryContainer,
                title = "Downloads",
                onClick = { onNavigate(SettingsRoutes.DOWNLOADS) },
            )
            SettingsNavRow(
                icon = Icons.Filled.SettingsBackupRestore,
                iconTint = cs.onSecondaryContainer,
                iconContainerColor = cs.secondaryContainer,
                title = "Backup & Restore",
                subtitle = "Export/import app preferences",
                onClick = { onNavigate(SettingsRoutes.BACKUP) },
            )
            SettingsNavRow(
                icon = Icons.Filled.Build,
                iconTint = cs.onSecondaryContainer,
                iconContainerColor = cs.secondaryContainer,
                title = "Advanced",
                subtitle = "System stats",
                onClick = { onNavigate(SettingsRoutes.ADVANCED) },
            )
            SettingsNavRow(
                icon = Icons.Filled.PhoneAndroid,
                iconTint = cs.onTertiaryContainer,
                iconContainerColor = cs.tertiaryContainer,
                title = "Device Capabilities",
                onClick = { onNavigate(SettingsRoutes.DEVICE_CAPABILITIES) },
            )
            SettingsNavRow(
                icon = Icons.Filled.BugReport,
                iconTint = cs.onSecondaryContainer,
                iconContainerColor = cs.secondaryContainer,
                title = "Developer Options",
                onClick = { onNavigate(SettingsRoutes.DEVELOPER_OPTIONS) },
            )
            SettingsNavRow(
                icon = Icons.Filled.Info,
                iconTint = cs.onPrimaryContainer,
                iconContainerColor = cs.primaryContainer,
                title = "About",
                onClick = { onNavigate(SettingsRoutes.ABOUT) },
            )
        }
    }
}
