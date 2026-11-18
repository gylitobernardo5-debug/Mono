package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GvpnColorScheme =
  darkColorScheme(
    primary = NeonGreen,
    onPrimary = TerminalBlack,
    primaryContainer = NeonGreenContainer,
    onPrimaryContainer = NeonGreen,
    secondary = ElectricBlue,
    onSecondary = TerminalBlack,
    secondaryContainer = ElectricBlueContainer,
    onSecondaryContainer = ElectricBlue,
    tertiary = TerminalAmber,
    onTertiary = TerminalBlack,
    background = TerminalBlack,
    onBackground = TerminalTextPrimary,
    surface = TerminalSurface,
    onSurface = TerminalTextPrimary,
    surfaceVariant = TerminalCard,
    onSurfaceVariant = TerminalTextMuted,
    surfaceContainerHigh = TerminalCardAlt,
    outline = TerminalBorder,
    outlineVariant = TerminalBorder,
    error = TerminalCrimson,
    onError = TerminalTextPrimary,
    errorContainer = TerminalCrimsonContainer,
    onErrorContainer = TerminalCrimson,
  )

@Composable
fun GvpnTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = GvpnColorScheme,
    typography = GvpnTypography,
    content = content,
  )
}
