package com.hypershare.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Brand = Color(0xFF0B5FFF)

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7EEFF),
    onPrimaryContainer = Color(0xFF0A2A6B),
    secondary = Color(0xFF1B2433),
    secondaryContainer = Color(0xFFE7EEFF),
    onSecondaryContainer = Color(0xFF0A2A6B),
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF0E1420),
    surface = Color.White,
    onSurface = Color(0xFF0E1420),
    surfaceVariant = Color(0xFFEDEFF2),
    onSurfaceVariant = Color(0xFF5B6372),
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    outline = Color(0xFFD6DAE1),
    outlineVariant = Color(0xFFE6E9EE),
    error = Color(0xFFD0342C),
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFF7A1A14),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4F8CFF),
    onPrimary = Color(0xFF00184A),
    primaryContainer = Color(0xFF12295A),
    onPrimaryContainer = Color(0xFFD9E5FF),
    secondary = Color(0xFFE6E9EF),
    secondaryContainer = Color(0xFF12295A),
    onSecondaryContainer = Color(0xFFD9E5FF),
    background = Color(0xFF0A0C10),
    onBackground = Color(0xFFE9ECF1),
    surface = Color(0xFF12151B),
    onSurface = Color(0xFFE9ECF1),
    surfaceVariant = Color(0xFF1A1E26),
    onSurfaceVariant = Color(0xFF9AA2B1),
    surfaceContainerLow = Color(0xFF12151B),
    surfaceContainer = Color(0xFF12151B),
    surfaceContainerHigh = Color(0xFF171B22),
    outline = Color(0xFF2B313C),
    outlineVariant = Color(0xFF20252E),
    error = Color(0xFFFF6B60),
    errorContainer = Color(0xFF3A1513),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val base = Typography()

/** Tight headlines, tabular figures for anything numeric. */
private val Type = base.copy(
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp, fontFeatureSettings = "tnum"),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFeatureSettings = "tnum", letterSpacing = 0.1.sp),
    bodySmall = base.bodySmall.copy(fontFeatureSettings = "tnum"),
)

val Numeric = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun HyperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
