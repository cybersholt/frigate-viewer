package net.triton.frigateviewer

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.materialkolor.PaletteStyle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.triton.frigateviewer.core.crash.CrashReportStore
import net.triton.frigateviewer.core.data.UserSettingsRepository
import net.triton.frigateviewer.feature.cameras.CamerasScreen
import net.triton.frigateviewer.feature.crash.CrashReportScreen
import net.triton.frigateviewer.feature.events.EventDetailScreen
import net.triton.frigateviewer.feature.events.EventsScreen
import net.triton.frigateviewer.feature.settings.AboutSettingsScreen
import net.triton.frigateviewer.feature.settings.AdvancedSettingsScreen
import net.triton.frigateviewer.feature.settings.AppearanceSettingsScreen
import net.triton.frigateviewer.feature.settings.CamerasViewSettingsScreen
import net.triton.frigateviewer.feature.settings.DeveloperOptionsSettingsScreen
import net.triton.frigateviewer.feature.settings.DeviceCapabilitiesSettingsScreen
import net.triton.frigateviewer.feature.settings.DownloadsSettingsScreen
import net.triton.frigateviewer.feature.settings.EventsSettingsScreen
import net.triton.frigateviewer.feature.settings.NotificationsSettingsScreen
import net.triton.frigateviewer.feature.settings.ServersSettingsScreen
import net.triton.frigateviewer.feature.settings.SettingsRoutes
import net.triton.frigateviewer.feature.settings.SettingsScreen
import net.triton.frigateviewer.feature.settings.StreamingSettingsScreen
import net.triton.frigateviewer.ui.theme.FrigateViewerTheme
import net.triton.frigateviewer.ui.theme.ThemeMode
import javax.inject.Inject

val LocalFullScreenMode =
    compositionLocalOf<MutableState<Boolean>> {
        error("LocalFullScreenMode not provided")
    }

/** Set by a focused live-view tile while composed, so [MainActivity.onUserLeaveHint] knows whether to auto-enter PiP. */
val LocalPipEligible =
    compositionLocalOf<MutableState<Boolean>> {
        error("LocalPipEligible not provided")
    }

/** True while the Activity is actually in Picture-in-Picture mode — live tiles hide their chrome/overlays. */
val LocalIsInPip = compositionLocalOf { false }

/** Requests entry into Picture-in-Picture (the explicit button, distinct from the home-press auto-trigger). */
val LocalEnterPipRequest = compositionLocalOf<() -> Unit> { {} }

/** Parsed target from a `frigateviewer://` deep link (HA motion-alert notifications). */
private data class DeepLinkTarget(
    val camera: String?,
    val eventId: String?,
)

/**
 * Resolves `frigateviewer://live?camera=<cam>` and `frigateviewer://event?id=<id>&camera=<cam>`.
 * Also tolerates senders (e.g. HA's `intent://` wrapping) that deliver `camera`/`id` as plain
 * string extras instead of encoding them in the data URI.
 */
private fun resolveDeepLink(intent: Intent?): DeepLinkTarget? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val data: Uri? = intent.data
    if (data != null && data.scheme == "frigateviewer") {
        val camera = data.getQueryParameter("camera") ?: intent.getStringExtra("camera")
        val id = data.getQueryParameter("id") ?: intent.getStringExtra("id")
        return when (data.host) {
            "live" -> DeepLinkTarget(camera = camera, eventId = null)
            "event" -> DeepLinkTarget(camera = camera, eventId = id)
            else -> null
        }
    }
    val camera = intent.getStringExtra("camera")
    val id = intent.getStringExtra("id")
    return when {
        id != null -> DeepLinkTarget(camera = camera, eventId = id)
        camera != null -> DeepLinkTarget(camera = camera, eventId = null)
        else -> null
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var userSettingsRepo: UserSettingsRepository

    private val deepLinkTarget = mutableStateOf<DeepLinkTarget?>(null)
    private val pipEligible = mutableStateOf(false)
    private val isInPip = mutableStateOf(false)

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            runCatching { enterPictureInPictureMode(params) }
        }
    }

    // Home-press/recents while a live view is focused enters PiP automatically, matching
    // the OS convention for video apps (YouTube, Google Home, etc.).
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEligible.value) enterPipMode()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip.value = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkTarget.value = resolveDeepLink(intent)
        setContent {
            val themeModeStr by userSettingsRepo.themeMode.collectAsState("SYSTEM")
            val accentColorLong by userSettingsRepo.accentColor.collectAsState(0xFF6750A4L)
            val useWallpaper by userSettingsRepo.useWallpaperColor.collectAsState(false)
            val paletteStyleStr by userSettingsRepo.paletteStyle.collectAsState("TonalSpot")
            val amoledBlack by userSettingsRepo.amoledBlack.collectAsState(false)
            val contrastLevel by userSettingsRepo.contrastLevel.collectAsState(0)
            val cardCornerRadius by userSettingsRepo.cardCornerRadius.collectAsState(12)
            val cardBorderWidth by userSettingsRepo.cardBorderWidth.collectAsState(0)

            val themeMode =
                try {
                    ThemeMode.valueOf(themeModeStr)
                } catch (e: Exception) {
                    ThemeMode.SYSTEM
                }
            val paletteStyle =
                try {
                    PaletteStyle.valueOf(paletteStyleStr)
                } catch (e: Exception) {
                    PaletteStyle.TonalSpot
                }

            FrigateViewerTheme(
                themeMode = themeMode,
                seedColor = Color(accentColorLong.toInt()),
                useWallpaperColor = useWallpaper,
                paletteStyle = paletteStyle,
                amoledBlack = amoledBlack,
                contrastLevel = contrastLevel,
                cardCornerRadius = cardCornerRadius,
                cardBorderWidth = cardBorderWidth,
            ) {
                AppRoot(
                    deepLinkTarget = deepLinkTarget,
                    pipEligible = pipEligible,
                    isInPip = isInPip,
                    onEnterPip = ::enterPipMode,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkTarget.value = resolveDeepLink(intent)
    }
}

private sealed class Dest(
    val route: String,
    val label: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    data object Cameras : Dest("cameras", R.string.nav_cameras, Icons.Filled.Videocam)

    data object Events : Dest("events", R.string.nav_events, Icons.Filled.Warning)

    data object Settings : Dest("settings", R.string.nav_settings, Icons.Filled.Settings)
}

private val tabs = listOf(Dest.Cameras, Dest.Events, Dest.Settings)

// Route with an optional camera to auto-focus (live?camera= deep links land here)
private const val CAMERAS_ROUTE = "cameras?camera={camera}"

private fun camerasRouteWith(camera: String? = null): String = if (camera != null) "cameras?camera=$camera" else "cameras"

// Route for events with optional filter query params
private const val EVENTS_ROUTE = "events?camera={camera}&label={label}&zone={zone}"

private fun eventsRouteWith(
    camera: String? = null,
    label: String? = null,
    zone: String? = null,
): String {
    val params =
        buildList {
            if (camera != null) add("camera=$camera")
            if (label != null) add("label=$label")
            if (zone != null) add("zone=$zone")
        }
    return if (params.isEmpty()) "events" else "events?${params.joinToString("&")}"
}

@Composable
private fun AppRoot(
    deepLinkTarget: MutableState<DeepLinkTarget?> = remember { mutableStateOf(null) },
    pipEligible: MutableState<Boolean> = remember { mutableStateOf(false) },
    isInPip: MutableState<Boolean> = remember { mutableStateOf(false) },
    onEnterPip: () -> Unit = {},
) {
    // Checked once per cold launch; if a crash was persisted last run, show its details instead
    // of the normal nav graph until dismissed. See core/crash/CrashHandler.kt.
    val context = LocalContext.current
    var crashCheckDone by remember { mutableStateOf(false) }
    var crashReport by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crashReport = CrashReportStore.readAndClear(context)
        crashCheckDone = true
    }
    if (!crashCheckDone) return
    if (crashReport != null) {
        CrashReportScreen(crashText = crashReport!!, onDismiss = { crashReport = null })
        return
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    // A destination is "on tab" if its route starts with a tab's base route
    val onTab = tabs.any { tab -> current?.route?.startsWith(tab.route) == true }
    val fullScreen = remember { mutableStateOf(false) }

    // Routes a frigateviewer:// deep link (cold or warm start) into the existing nav
    // graph, then clears the target so it isn't re-applied on the next recomposition.
    LaunchedEffect(deepLinkTarget.value) {
        val target = deepLinkTarget.value ?: return@LaunchedEffect
        if (target.eventId != null) {
            nav.navigate("event/${target.eventId}") { launchSingleTop = true }
        } else if (target.camera != null) {
            nav.navigate(camerasRouteWith(target.camera)) {
                launchSingleTop = true
                restoreState = true
                popUpTo(nav.graph.startDestinationId) { saveState = true }
            }
        }
        deepLinkTarget.value = null
    }

    CompositionLocalProvider(
        LocalFullScreenMode provides fullScreen,
        LocalPipEligible provides pipEligible,
        LocalIsInPip provides isInPip.value,
        LocalEnterPipRequest provides onEnterPip,
    ) {
        Scaffold(
            // m3 1.5.0 Scaffold pads content by displayCutout (union'd into the default
            // insets) — the cutout inset never reaches zero even with system bars hidden,
            // so fullscreen landscape would show a containerColor strip on the punch-hole
            // edge. Zero insets + black container while fullscreen.
            containerColor = if (fullScreen.value) Color.Black else MaterialTheme.colorScheme.background,
            contentWindowInsets = if (fullScreen.value) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
            bottomBar = {
                if (onTab && !fullScreen.value && !isInPip.value) {
                    Column(Modifier.background(NavigationBarDefaults.containerColor)) {
                        NavigationBar(
                            modifier = Modifier.height(68.dp),
                            windowInsets = WindowInsets(0),
                            containerColor = Color.Transparent,
                        ) {
                            tabs.forEach { tab ->
                                val selected =
                                    current?.hierarchy?.any { it.route?.startsWith(tab.route) == true } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        nav.navigate(tab.route) {
                                            launchSingleTop = true
                                            restoreState = true
                                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            tab.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    },
                                    label = {
                                        Text(
                                            stringResource(tab.label),
                                            fontSize = 10.sp,
                                        )
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars))
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = CAMERAS_ROUTE,
                modifier = Modifier.padding(padding),
            ) {
                composable(
                    route = CAMERAS_ROUTE,
                    arguments =
                        listOf(
                            navArgument("camera") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                ) { entry ->
                    CamerasScreen(
                        initialFocusedCamera = entry.arguments?.getString("camera"),
                        onNavigateToEvents = { camera, label, zone ->
                            nav.navigate(eventsRouteWith(camera, label, zone)) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                            }
                        },
                    )
                }
                composable(
                    route = EVENTS_ROUTE,
                    arguments =
                        listOf(
                            navArgument("camera") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            navArgument("label") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            navArgument("zone") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                ) { entry ->
                    EventsScreen(
                        onEventClick = { id -> nav.navigate("event/$id") },
                        initialCamera = entry.arguments?.getString("camera"),
                        initialLabel = entry.arguments?.getString("label"),
                        initialZone = entry.arguments?.getString("zone"),
                    )
                }
                composable(Dest.Settings.route) {
                    SettingsScreen(onNavigate = { route -> nav.navigate(route) })
                }
                composable(SettingsRoutes.SERVERS) { ServersSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsRoutes.APPEARANCE) { AppearanceSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsRoutes.CAMERAS_VIEW) { CamerasViewSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsRoutes.STREAMING) { StreamingSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsRoutes.EVENTS) { EventsSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsRoutes.NOTIFICATIONS) { NotificationsSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsRoutes.DOWNLOADS) { DownloadsSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsRoutes.ADVANCED) { AdvancedSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsRoutes.DEVICE_CAPABILITIES) { DeviceCapabilitiesSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsRoutes.DEVELOPER_OPTIONS) { DeveloperOptionsSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsRoutes.ABOUT) { AboutSettingsScreen(onBack = { nav.popBackStack() }) }
                composable("event/{id}") { entry ->
                    val id = entry.arguments?.getString("id") ?: return@composable
                    EventDetailScreen(
                        eventId = id,
                        onNavigateToEvents = { camera, label, zone ->
                            nav.navigate(eventsRouteWith(camera, label, zone)) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                            }
                        },
                        onNavigateToEvent = { newId ->
                            nav.navigate("event/$newId")
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    } // CompositionLocalProvider
}
