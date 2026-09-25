package com.lotanbar.tripexplorer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Primary = Color(0xFF2196F3)
val PrimaryDark = Color(0xFF1565C0)
val Surface = Color(0xFF1A1A1A)
val Background = Color(0xFF121212)
val OnSurface = Color(0xFFE0E0E0)

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    surface = Surface,
    background = Background,
    onSurface = OnSurface,
)

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
)

@Composable
fun TripExplorerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography,
        content = content,
    )
}
