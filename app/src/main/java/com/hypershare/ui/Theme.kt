package com.hypershare.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Signature accent: volt lime. Always paired with Ink text, never white. */
val Volt = Color(0xFFD4FF3A)
val Ink = Color(0xFF111112)
val Brand = Volt

private val Light = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAFBB0),
    onPrimaryContainer = Color(0xFF26300A),
    secondary = Color(0xFF3A3A3D),
    secondaryContainer = Ink,
    onSecondaryContainer = Color.White,
    background = Color(0xFFF2F1EC),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9E8E2),
    onSurfaceVariant = Color(0xFF66655F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F7F3),
    surfaceContainer = Color(0xFFF2F1EC),
    surfaceContainerHigh = Color(0xFFEBEAE4),
    surfaceContainerHighest = Color(0xFFE3E2DB),
    inverseSurface = Ink,
    inverseOnSurface = Color.White,
    tertiary = Color(0xFF1F9D55),
    outline = Color(0xFFD3D2CB),
    outlineVariant = Color(0xFFE6E5DF),
    error = Color(0xFFD7372B),
    errorContainer = Color(0xFFFCE8E5),
    onErrorContainer = Color(0xFF6E140D),
)

private val Dark = darkColorScheme(
    primary = Volt,
    onPrimary = Ink,
    primaryContainer = Color(0xFF29310B),
    onPrimaryContainer = Color(0xFFE3FF85),
    secondary = Color(0xFFD9D9D4),
    secondaryContainer = Volt,
    onSecondaryContainer = Ink,
    background = Color(0xFF0B0B0C),
    onBackground = Color(0xFFF1F1EC),
    surface = Color(0xFF161618),
    onSurface = Color(0xFFF1F1EC),
    surfaceVariant = Color(0xFF1E1E21),
    onSurfaceVariant = Color(0xFF9B9A94),
    surfaceContainerLowest = Color(0xFF0E0E10),
    surfaceContainerLow = Color(0xFF131315),
    surfaceContainer = Color(0xFF18181A),
    surfaceContainerHigh = Color(0xFF1F1F22),
    surfaceContainerHighest = Color(0xFF27272B),
    inverseSurface = Color(0xFFF1F1EC),
    inverseOnSurface = Ink,
    tertiary = Color(0xFF5BE38F),
    outline = Color(0xFF34343A),
    outlineVariant = Color(0xFF232327),
    error = Color(0xFFFF6B5E),
    errorContainer = Color(0xFF3A1411),
    onErrorContainer = Color(0xFFFFDAD5),
)

private val base = Typography()

/** Heavy, tight headlines; tabular figures for anything numeric. */
private val Type = base.copy(
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-2).sp, fontFeatureSettings = "tnum"),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1.4).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 23.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp),
    bodySmall = base.bodySmall.copy(lineHeight = 17.sp, fontFeatureSettings = "tnum"),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFeatureSettings = "tnum", letterSpacing = 0.1.sp),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Hero surface fill (Send tile, logo). Solid volt: no gradients. */
val BrandGradient = Brush.linearGradient(listOf(Volt, Volt))

/** Mono for codes, section labels, technical meta. */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")

val Numeric = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun HyperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, shapes = Shape, content = content)
}
