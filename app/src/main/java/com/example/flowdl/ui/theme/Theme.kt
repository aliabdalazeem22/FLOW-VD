package com.example.flowdl.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF625BFF), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E2FF), onPrimaryContainer = Color(0xFF211A83),
    secondary = Color(0xFF087F83), onSecondary = Color.White,
    secondaryContainer = Color(0xFFB6F2EA), onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF12BFAE), onTertiary = Color(0xFF00201D),
    background = Color(0xFFF5F6FC), onBackground = Color(0xFF17182B),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF17182B),
    surfaceVariant = Color(0xFFE9EAF5), onSurfaceVariant = Color(0xFF5E6078),
    outline = Color(0xFFB8B9CC), error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8B3FF), onPrimary = Color(0xFF201A79),
    primaryContainer = Color(0xFF3932A6), onPrimaryContainer = Color(0xFFE5E2FF),
    secondary = Color(0xFF7CE4D8), onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF075B56), onSecondaryContainer = Color(0xFFB6F2EA),
    tertiary = Color(0xFF26E2CB), onTertiary = Color(0xFF003731),
    background = Color(0xFF0B1020), onBackground = Color(0xFFF0F0FF),
    surface = Color(0xFF12192B), onSurface = Color(0xFFF0F0FF),
    surfaceVariant = Color(0xFF202A40), onSurfaceVariant = Color(0xFFB6BED4),
    outline = Color(0xFF414C66), error = Color(0xFFFFB4AB)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

@Composable
fun FlowDlTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(), shapes = AppShapes, content = content
    )
}
