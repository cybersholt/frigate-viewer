package net.triton.frigateviewer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Composable
fun FrigateViewerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    seedColor: Color = Color(0xFF6750A4),
    useWallpaperColor: Boolean = false,
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }

    val context = LocalContext.current

    if (useWallpaperColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val colorScheme =
            if (darkTheme) {
                dynamicDarkColorScheme(context).let {
                    if (amoledBlack) it.copy(surface = Color.Black, background = Color.Black) else it
                }
            } else {
                dynamicLightColorScheme(context)
            }
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    } else {
        DynamicMaterialTheme(
            seedColor = seedColor,
            isDark = darkTheme,
            style = paletteStyle,
            isAmoled = amoledBlack,
            animate = true,
            content = content,
        )
    }
}
