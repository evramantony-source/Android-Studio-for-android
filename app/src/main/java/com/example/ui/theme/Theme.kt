package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = ForgeCyan,
    onPrimary = Color(0xFF04131E),
    primaryContainer = Color(0xFF0E2942),
    onPrimaryContainer = Color(0xFFBFE9FF),
    secondary = ForgeEmerald,
    onSecondary = Color(0xFF032116),
    secondaryContainer = Color(0xFF0E3026),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = ForgeIndigo,
    onTertiary = Color(0xFF0E1338),
    tertiaryContainer = Color(0xFF1F264D),
    onTertiaryContainer = Color(0xFFE0E7FF),
    background = ObsidianBg,
    onBackground = ObsidianText,
    surface = ObsidianSurface,
    onSurface = ObsidianText,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = ObsidianTextMuted,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceHigh,
    surfaceContainerHigh = ObsidianSurfaceHighest,
    outline = ObsidianOutline,
    outlineVariant = Color(0xFF1F293D),
    error = ForgeCoral,
    onError = Color(0xFF2D0A0A),
    errorContainer = Color(0xFF45161B),
    onErrorContainer = Color(0xFFFECACA),
)

private val LightColorScheme = lightColorScheme(
    primary = ForgeCyanDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF2FE),
    onPrimaryContainer = Color(0xFF082F49),
    secondary = ForgeEmeraldDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFF4F46E5),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E7FF),
    onTertiaryContainer = Color(0xFF1E1B4B),
    background = DaylightBg,
    onBackground = DaylightText,
    surface = DaylightSurface,
    onSurface = DaylightText,
    surfaceVariant = DaylightSurfaceHigh,
    onSurfaceVariant = DaylightTextMuted,
    surfaceContainerLow = DaylightSurface,
    surfaceContainer = DaylightSurfaceHigh,
    surfaceContainerHigh = DaylightSurfaceHighest,
    outline = DaylightOutline,
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DroidForgeShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

@Composable
fun DroidForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val syntaxColors = if (darkTheme) DarkSyntaxColors else LightSyntaxColors

    CompositionLocalProvider(LocalSyntaxColors provides syntaxColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DroidForgeTypography,
            shapes = DroidForgeShapes,
            content = content,
        )
    }
}
