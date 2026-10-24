package com.okusezu.hummm.ui

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Looks like the desktop app in a terminal: one flat black surface, grey
 * rules instead of cards, monospace text and no rounded corners anywhere.
 * The accent comes from the wallpaper (Material You), the phone's stand-in
 * for the terminal palette; older phones get amber.
 */

private val Bg = Color(0xFF0C0C0C)
private val Fg = Color(0xFFD4D4D4)
private val Dim = Color(0xFF8A8A8A)
private val Rule = Color(0xFF3A3A3A)
private val Raised = Color(0xFF161616)

private fun terminal(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Bg,
    primaryContainer = accent,
    onPrimaryContainer = Bg,
    secondary = accent,
    onSecondary = Bg,
    secondaryContainer = Raised,
    onSecondaryContainer = Fg,
    tertiary = accent,
    background = Bg,
    onBackground = Fg,
    surface = Bg,
    onSurface = Fg,
    surfaceVariant = Raised,
    onSurfaceVariant = Dim,
    surfaceTint = Color.Transparent,
    surfaceBright = Raised,
    surfaceDim = Bg,
    surfaceContainerLowest = Bg,
    surfaceContainerLow = Bg,
    surfaceContainer = Bg,
    surfaceContainerHigh = Raised,
    surfaceContainerHighest = Raised,
    inverseSurface = Fg,
    inverseOnSurface = Bg,
    outline = Rule,
    outlineVariant = Rule,
)

private val Sharp = RoundedCornerShape(0.dp)
private val Square = Shapes(extraSmall = Sharp, small = Sharp, medium = Sharp, large = Sharp, extraLarge = Sharp)

private fun TextStyle.mono() = copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)

private val Mono = Typography().run {
    Typography(
        displayLarge = displayLarge.mono(), displayMedium = displayMedium.mono(), displaySmall = displaySmall.mono(),
        headlineLarge = headlineLarge.mono(), headlineMedium = headlineMedium.mono(),
        headlineSmall = headlineSmall.mono().copy(fontSize = 21.sp, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.mono().copy(fontSize = 19.sp, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.mono().copy(fontWeight = FontWeight.Bold),
        titleSmall = titleSmall.mono(),
        bodyLarge = bodyLarge.mono().copy(fontSize = 15.sp), bodyMedium = bodyMedium.mono().copy(fontSize = 13.sp),
        bodySmall = bodySmall.mono(),
        labelLarge = labelLarge.mono(), labelMedium = labelMedium.mono(), labelSmall = labelSmall.mono(),
    )
}

@Composable
fun HummmTheme(content: @Composable () -> Unit) {
    val accent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        dynamicDarkColorScheme(LocalContext.current).primary
    else Color(0xFFFFB86B)
    MaterialTheme(colorScheme = terminal(accent), shapes = Square, typography = Mono, content = content)
}
