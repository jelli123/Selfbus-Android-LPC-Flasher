package com.selfbus.lpcflasher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val SelfbusGreen = Color(0xFF4CAF50)
private val SelfbusDarkGreen = Color(0xFF388E3C)
private val AccentOrange = Color(0xFFFF9800)

private val LightColorScheme = lightColorScheme(
    primary = SelfbusGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    secondary = AccentOrange,
    onSecondary = Color.White,
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    error = Color(0xFFD32F2F)
)

private val DarkColorScheme = darkColorScheme(
    primary = SelfbusGreen,
    onPrimary = Color.White,
    primaryContainer = SelfbusDarkGreen,
    secondary = AccentOrange,
    onSecondary = Color.Black,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    error = Color(0xFFEF5350)
)

@Composable
fun LpcFlasherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
