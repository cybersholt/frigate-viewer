package net.triton.frigateviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
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

@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val onTab = current?.route in tabs.map { it.route }
    val fullScreen = remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalFullScreenMode provides fullScreen) {
        Scaffold(
            bottomBar = {
                if (onTab && !fullScreen.value) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            val selected = current?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        launchSingleTop = true
                                        restoreState = true
                                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Dest.Cameras.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(Dest.Cameras.route) { CamerasScreen() }
                composable(Dest.Events.route) {
                    EventsScreen(onEventClick = { id -> nav.navigate("event/$id") })
                }
                composable(Dest.Settings.route) { SettingsScreen() }
                composable("event/{id}") { entry ->
                    val id = entry.arguments?.getString("id") ?: return@composable
                    EventDetailScreen(eventId = id)
                }
            }
        }
    } // CompositionLocalProvider
}
