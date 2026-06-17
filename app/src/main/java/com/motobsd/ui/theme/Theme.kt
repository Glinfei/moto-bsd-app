package com.motobsd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val MotoBsdBlue = Color(0xFF2196F3)
val SafeGray = Color(0xFF9E9E9E)
val WarningYellow = Color(0xFFFFC107)
val CriticalRed = Color(0xFFF44336)
val SafeBg = Color(0xFFF5F5F5)
val WarningBg = Color(0xFFFFF8E1)
val CriticalBg = Color(0xFFFFEBEE)

private val DarkColors = darkColorScheme(
    primary = MotoBsdBlue,
    secondary = Color(0xFF03DAC6),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun MotoBSDTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
