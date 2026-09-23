package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Pure AMOLED Dark (#000000)
private val AmoledDarkColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = TrueBlack,
    primaryContainer = SoftIndigo,
    onPrimaryContainer = Color.White,
    secondary = DarkTextSecondary,
    onSecondary = TrueBlack,
    background = TrueBlack,
    onBackground = DarkTextPrimary,
    surface = TrueBlack,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    outlineVariant = DarkBorder,
    error = DangerRed,
    onError = Color.White
)

// Clean Light (#FFFFFF)
private val CleanLightColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = PureWhite,
    primaryContainer = SoftIndigo,
    onPrimaryContainer = Color.White,
    secondary = LightTextSecondary,
    onSecondary = PureWhite,
    background = PureWhite,
    onBackground = LightTextPrimary,
    surface = PureWhite,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurface,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = LightBorder,
    error = DangerRed,
    onError = PureWhite
)

@Composable
fun TagOutTheme(
    themePreference: String = "dark", // "dark", "light", "system"
    content: @Composable () -> Unit
) {
    val isDark = when (themePreference.lowercase()) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true // default to AMOLED Dark
    }

    val colorScheme = if (isDark) AmoledDarkColorScheme else CleanLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    TagOutTheme(
        themePreference = if (darkTheme) "dark" else "light",
        content = content
    )
}
