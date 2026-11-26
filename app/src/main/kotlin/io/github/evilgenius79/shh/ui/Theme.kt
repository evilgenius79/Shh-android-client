package io.github.evilgenius79.shh.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.evilgenius79.shh.data.Settings
import io.github.evilgenius79.shh.data.ThemeMode

private val Green = Color(0xFF3F8F1B)
private val GreenLight = Color(0xFF8AE234)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F2BE),
    onPrimaryContainer = Color(0xFF0B2100),
    secondary = Color(0xFF55624C),
    tertiary = Color(0xFF386667),
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color(0xFF0F3900),
    primaryContainer = Color(0xFF1F5200),
    onPrimaryContainer = Color(0xFFC8F5A8),
    secondary = Color(0xFFBDCBB0),
    tertiary = Color(0xFFA0CFD0),
    background = Color(0xFF101214),
    surface = Color(0xFF101214),
)

/** True when the app should use its dark palette given the user's choice and the system state. */
@Composable
fun useDarkTheme(settings: Settings): Boolean = when (settings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun ShhTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = useDarkTheme(settings)
    val colors = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
