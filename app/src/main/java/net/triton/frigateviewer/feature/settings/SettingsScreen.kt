package net.triton.frigateviewer.feature.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Route strings for the Settings nav graph — single source of truth, referenced from MainActivity's NavHost. */
object SettingsRoutes {
    const val ROOT = "settings"
    const val SERVERS = "settings/servers"
    const val APPEARANCE = "settings/appearance"
    const val CAMERAS_VIEW = "settings/cameras_view"
    const val STREAMING = "settings/streaming"
    const val EVENTS = "settings/events"
    const val NOTIFICATIONS = "settings/notifications"
    const val BACKUP = "settings/backup"
    const val ADVANCED = "settings/advanced"
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
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minHeaderHeight = 64.dp + statusBarHeight
    val maxHeaderHeight = 120.dp + statusBarHeight
    val minHeaderHeightPx = with(density) { minHeaderHeight.toPx() }
    val maxHeaderHeightPx = with(density) { maxHeaderHeight.toPx() }

    val headerHeight = remember(maxHeaderHeightPx) { Animatable(maxHeaderHeightPx) }
    var collapseFraction by remember { mutableStateOf(0f) }

    LaunchedEffect(headerHeight.value, maxHeaderHeightPx) {
        collapseFraction =
            1f - ((headerHeight.value - minHeaderHeightPx) / (maxHeaderHeightPx - minHeaderHeightPx))
                .coerceIn(0f, 1f)
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val isScrollingDown = delta < 0
                if (!isScrollingDown && scrollState.value > 0) return Offset.Zero

                val previousHeight = headerHeight.value
                val newHeight = (previousHeight + delta).coerceIn(minHeaderHeightPx, maxHeaderHeightPx)
                val consumed = newHeight - previousHeight
                if (consumed.roundToInt() != 0) {
                    scope.launch { headerHeight.snapTo(newHeight) }
                }
                val canConsumeScroll = !(isScrollingDown && newHeight == minHeaderHeightPx)
                return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
            }
        }
    }

    LaunchedEffect(scrollState.isScrollInProgress) {
        if (!scrollState.isScrollInProgress) {
            val shouldExpand = headerHeight.value > (minHeaderHeightPx + maxHeaderHeightPx) / 2
            val canExpand = scrollState.value == 0
            val target = if (shouldExpand && canExpand) maxHeaderHeightPx else minHeaderHeightPx
            if (headerHeight.value != target) {
                scope.launch { headerHeight.animateTo(target, spring(stiffness = Spring.StiffnessMedium)) }
            }
        }
    }

    Box(Modifier.nestedScroll(nestedScrollConnection).fillMaxSize()) {
        val currentHeaderHeightDp = with(density) { headerHeight.value.toDp() }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = currentHeaderHeightDp)
                .padding(horizontal = 16.dp),
        ) {
            val cs = MaterialTheme.colorScheme
            val activeServerName = state.servers.find { it.id == state.activeId }?.name
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = cs.surfaceVariant.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingsNavRow(
                        icon = Icons.Filled.Dns,
                        iconTint = cs.onPrimaryContainer,
                        iconContainerColor = cs.primaryContainer.copy(alpha = 0.4f),
                        title = "Servers",
                        subtitle = activeServerName ?: "No server configured",
                        onClick = { onNavigate(SettingsRoutes.SERVERS) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavRow(
                        icon = Icons.Filled.Palette,
                        iconTint = cs.onSecondaryContainer,
                        iconContainerColor = cs.secondaryContainer.copy(alpha = 0.4f),
                        title = "Appearance",
                        subtitle = "Themes, layout, and visual styles",
                        onClick = { onNavigate(SettingsRoutes.APPEARANCE) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavRow(
                        icon = Icons.Filled.GridView,
                        iconTint = cs.onTertiaryContainer,
                        iconContainerColor = cs.tertiaryContainer.copy(alpha = 0.4f),
                        title = "Cameras View",
                        subtitle = "Grid layout, refresh, bounding boxes",
                        onClick = { onNavigate(SettingsRoutes.CAMERAS_VIEW) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavRow(
                        icon = Icons.Filled.PlayCircle,
                        iconTint = cs.onPrimaryContainer,
                        iconContainerColor = cs.primaryContainer.copy(alpha = 0.4f),
                        title = "Streaming",
                        subtitle = "Live stream type, sub stream",
                        onClick = { onNavigate(SettingsRoutes.STREAMING) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavRow(
                        icon = Icons.AutoMirrored.Filled.EventNote,
                        iconTint = cs.onSecondaryContainer,
                        iconContainerColor = cs.secondaryContainer.copy(alpha = 0.4f),
                        title = "Events",
                        subtitle = "Grid, photo preference, date format",
                        onClick = { onNavigate(SettingsRoutes.EVENTS) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavRow(
                        icon = Icons.Filled.Notifications,
                        iconTint = cs.onTertiaryContainer,
                        iconContainerColor = cs.tertiaryContainer.copy(alpha = 0.4f),
                        title = "Notifications",
                        onClick = { onNavigate(SettingsRoutes.NOTIFICATIONS) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavRow(
                        icon = Icons.Filled.SettingsBackupRestore,
                        iconTint = cs.onSecondaryContainer,
                        iconContainerColor = cs.secondaryContainer.copy(alpha = 0.4f),
                        title = "Backup & Restore",
                        subtitle = "Export/import app preferences",
                        onClick = { onNavigate(SettingsRoutes.BACKUP) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavRow(
                        icon = Icons.Filled.Build,
                        iconTint = cs.onSecondaryContainer,
                        iconContainerColor = cs.secondaryContainer.copy(alpha = 0.4f),
                        title = "Advanced",
                        subtitle = "System stats",
                        onClick = { onNavigate(SettingsRoutes.ADVANCED) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavRow(
                        icon = Icons.Filled.BugReport,
                        iconTint = cs.onSecondaryContainer,
                        iconContainerColor = cs.secondaryContainer.copy(alpha = 0.4f),
                        title = "Developer Options",
                        onClick = { onNavigate(SettingsRoutes.DEVELOPER_OPTIONS) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                    SettingsNavRow(
                        icon = Icons.Filled.Info,
                        iconTint = cs.onPrimaryContainer,
                        iconContainerColor = cs.primaryContainer.copy(alpha = 0.4f),
                        title = "About",
                        onClick = { onNavigate(SettingsRoutes.ABOUT) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        val surfaceAlpha = (collapseFraction * 2f).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(currentHeaderHeightDp)
                .background(MaterialTheme.colorScheme.background.copy(alpha = surfaceAlpha))
                .zIndex(5f)
        ) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                val titlePaddingStart = 20.dp
                val titleVerticalBias = lerp(0.3f, 0f, collapseFraction)
                val titleScale = lerp(1.1f, 0.8f, collapseFraction)

                Box(
                    Modifier
                        .align(BiasAlignment(horizontalBias = -1f, verticalBias = titleVerticalBias))
                        .padding(start = titlePaddingStart)
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.graphicsLayer {
                            scaleX = titleScale
                            scaleY = titleScale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                    )
                }
            }
        }
    }
}
