package net.triton.frigateviewer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle

val LocalCardBorderWidth = compositionLocalOf { 0.dp }

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
    contrastLevel: Int = 0,
    cardCornerRadius: Int = 12,
    cardBorderWidth: Int = 0,
    typography: Typography = AppTypography,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }

    val mFloat = cardCornerRadius.toFloat()
    val shapes =
        Shapes(
            extraSmall = RoundedCornerShape((mFloat / 3f).dp),
            small = RoundedCornerShape((mFloat * 2f / 3f).dp),
            medium = RoundedCornerShape(cardCornerRadius.dp),
            large = RoundedCornerShape(cardCornerRadius.dp),
            extraLarge = RoundedCornerShape(cardCornerRadius.dp),
        )
    val borderWidthDp: Dp = cardBorderWidth.dp

    val view = LocalView.current
    if (!view.isInEditMode) {
        val currentWindow = (view.context as? Activity)?.window
        SideEffect {
            currentWindow?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    val context = LocalContext.current

    val themedContent: @Composable () -> Unit = {
        CompositionLocalProvider(LocalCardBorderWidth provides borderWidthDp) {
            content()
        }
    }

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
            shapes = shapes,
            typography = typography,
            content = themedContent,
        )
    } else {
        DynamicMaterialTheme(
            seedColor = seedColor,
            isDark = darkTheme,
            style = paletteStyle,
            contrastLevel = contrastLevel.toDouble() / 100.0,
            isAmoled = amoledBlack,
            shapes = shapes,
            typography = typography,
            animate = true,
            content = themedContent,
        )
    }
}
