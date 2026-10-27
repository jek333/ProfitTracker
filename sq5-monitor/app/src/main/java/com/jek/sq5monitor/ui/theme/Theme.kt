package com.jek.sq5monitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Always dark: the app is mostly used in a car, often at night. */
private val Colors = darkColorScheme(
    primary = Color(0xFFE0283C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C0E18),
    onPrimaryContainer = Color(0xFFFFDADB),
    secondary = Color(0xFFB8C4CF),
    onSecondary = Color(0xFF1C252D),
    secondaryContainer = Color(0xFF2B343C),
    onSecondaryContainer = Color(0xFFD7E0EA),
    tertiary = Color(0xFF6FD3A6),
    background = Color(0xFF0E0F11),
    onBackground = Color(0xFFE6E7EA),
    surface = Color(0xFF0E0F11),
    onSurface = Color(0xFFE6E7EA),
    surfaceVariant = Color(0xFF22252A),
    onSurfaceVariant = Color(0xFFB4B8BF),
    surfaceContainerLowest = Color(0xFF0A0B0D),
    surfaceContainerLow = Color(0xFF15171A),
    surfaceContainer = Color(0xFF191B1F),
    surfaceContainerHigh = Color(0xFF202328),
    surfaceContainerHighest = Color(0xFF2A2D33),
    outline = Color(0xFF4A4F57),
    outlineVariant = Color(0xFF30343A),
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF5A1414),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Semantic colours beyond the Material scheme. */
object StatusColors {
    val ok = Color(0xFF6FD3A6)
    val warning = Color(0xFFFFB547)
    val critical = Color(0xFFFF6B6B)
    val inactive = Color(0xFF6C717A)
}

@Composable
fun Sq5Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
