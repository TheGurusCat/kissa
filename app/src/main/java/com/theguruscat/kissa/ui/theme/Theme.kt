package com.theguruscat.kissa.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The app sits on dark gray in light and dark mode alike; the cards are beige paper on top of it.
private val AppColors = darkColorScheme(
    background = Color(0xFF2B2B2B),
    surface = Color(0xFF2B2B2B),
    surfaceContainerLowest = Color(0xFF222222),
    surfaceContainerLow = Color(0xFF303030),
    surfaceContainer = Color(0xFF353535),
    surfaceContainerHigh = Color(0xFF3D3D3D),
    surfaceContainerHighest = Color(0xFF464646),
)

private val CardColors = lightColorScheme(
    primary = Color(0xFF7A4E1D),
    tertiary = Color(0xFF8A5A00),
    surface = Color(0xFFF3EAD7),
    onSurface = Color(0xFF2E2820),
    onSurfaceVariant = Color(0xFF5E5547),
    surfaceContainerLow = Color(0xFFF3EAD7),
    surfaceContainer = Color(0xFFF3EAD7),
    // The card itself (see SwipeCard), and the loading placeholder's bars.
    surfaceContainerHigh = Color(0xFFF3EAD7),
    surfaceContainerHighest = Color(0xFFE4D8C0),
    inverseSurface = Color(0xFF3A332A),
    inverseOnSurface = Color(0xFFF3EAD7),
)

@Composable
fun KissaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, content = content)
}

/** The beige colors cards are drawn in; wraps the swipe screen's card stack. */
@Composable
fun KissaCardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CardColors, typography = MaterialTheme.typography, content = content)
}
