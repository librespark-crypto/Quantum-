package com.quantum.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Quantum colour palette.
 *
 * These are the exact values the existing XML themes and screens were written
 * against: a very dark blue-black surface set with a single bright blue accent.
 */
object QuantumColors {
    val Background = Color(0xFF050810)
    val Surface = Color(0xFF0A0E17)
    val SurfaceVariant = Color(0xFF1A1F2E)
    val Primary = Color(0xFF0066FF)
    val PrimaryContainer = Color(0xFF1A1F2E)
    val OnPrimary = Color(0xFFFFFFFF)
    val OnSurface = Color(0xFFE8E8E8)
    val OnSurfaceVariant = Color(0xFFB0B0B0)
    val Outline = Color(0xFF333A4D)
    val Error = Color(0xFFFF5252)
    val Scrim = Color(0xCC000000)
}

private val DarkColors = darkColorScheme(
    primary = QuantumColors.Primary,
    onPrimary = QuantumColors.OnPrimary,
    primaryContainer = QuantumColors.PrimaryContainer,
    onPrimaryContainer = QuantumColors.OnSurface,
    secondary = QuantumColors.Primary,
    onSecondary = QuantumColors.OnPrimary,
    background = QuantumColors.Background,
    onBackground = QuantumColors.OnSurface,
    surface = QuantumColors.Surface,
    onSurface = QuantumColors.OnSurface,
    surfaceVariant = QuantumColors.SurfaceVariant,
    onSurfaceVariant = QuantumColors.OnSurfaceVariant,
    outline = QuantumColors.Outline,
    error = QuantumColors.Error
)

private val LightColors = lightColorScheme(
    primary = QuantumColors.Primary,
    onPrimary = QuantumColors.OnPrimary,
    background = QuantumColors.Background,
    onBackground = QuantumColors.OnSurface,
    surface = QuantumColors.Surface,
    onSurface = QuantumColors.OnSurface,
    surfaceVariant = QuantumColors.SurfaceVariant,
    onSurfaceVariant = QuantumColors.OnSurfaceVariant,
    error = QuantumColors.Error
)

private val QuantumTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp
    )
)

/**
 * Material 3 theme for Quantum.
 *
 * The player surface is always dark (a video player should not go light), so
 * [forceDark] defaults to true; the browser screens follow the system setting.
 */
@Composable
fun QuantumTheme(
    forceDark: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = forceDark || isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = QuantumTypography,
        content = content
    )
}
