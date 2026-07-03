package net.triton.frigateviewer

import android.os.Bundle
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.materialkolor.PaletteStyle
import dagger.hilt.android.AndroidEntryPoint
import net.triton.frigateviewer.core.data.UserSettingsRepository
import net.triton.frigateviewer.feature.cameras.CamerasScreen
import net.triton.frigateviewer.feature.events.EventDetailScreen
import net.triton.frigateviewer.feature.events.EventsScreen
import net.triton.frigateviewer.feature.settings.SettingsScreen
import net.triton.frigateviewer.ui.theme.FrigateViewerTheme
import net.triton.frigateviewer.ui.theme.ThemeMode
import javax.inject.Inject

val LocalFullScreenMode =
    compositionLocalOf<MutableState<Boolean>> {
        error("LocalFullScreenMode not provided")
    }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var userSettingsRepo: UserSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                seedColor = Color(accentColorLong),
                useWallpaperColor = useWallpaper,
                paletteStyle = paletteStyle,
                amoledBlack = amoledBlack,
                contrastLevel = contrastLevel,
                cardCornerRadius = cardCornerRadius,
                cardBorderWidth = cardBorderWidth,
            ) {
                AppRoot()
            }
        }
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
private fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    // A destination is "on tab" if its route starts with a tab's base route
    val onTab = tabs.any { tab -> current?.route?.startsWith(tab.route) == true }
    val fullScreen = remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalFullScreenMode provides fullScreen) {
        Scaffold(
            // m3 1.5.0 Scaffold pads content by displayCutout (union'd into the default
            // insets) — the cutout inset never reaches zero even with system bars hidden,
            // so fullscreen landscape would show a containerColor strip on the punch-hole
            // edge. Zero insets + black container while fullscreen.
            containerColor = if (fullScreen.value) Color.Black else MaterialTheme.colorScheme.background,
            contentWindowInsets = if (fullScreen.value) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
            bottomBar = {
                if (onTab && !fullScreen.value) {
                    Column {
                        NavigationBar(modifier = Modifier.height(68.dp), windowInsets = WindowInsets(0)) {
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
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .background(NavigationBarDefaults.containerColor),
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Dest.Cameras.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(Dest.Cameras.route) {
                    CamerasScreen(
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
                composable(Dest.Settings.route) { SettingsScreen() }
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
