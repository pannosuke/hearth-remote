package net.pcg.hearth.remote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFC107),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFFFFA000),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFFFFC107),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF3D3D3D),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFFFD54F),
    onTertiary = Color(0xFF000000),
    background = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFFB3B3B3),
    outline = Color(0xFF5C5C5C),
    error = Color(0xFFF44336),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun HearthRemoteTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
