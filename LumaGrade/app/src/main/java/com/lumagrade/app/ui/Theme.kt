package com.lumagrade.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Acid = Color(0xFFD6FF63)
val Ink = Color(0xFF0B0C0F)
val Panel = Color(0xFF15171C)
val SoftPanel = Color(0xFF20232A)
val Muted = Color(0xFFA8ABB3)

private val LumaGradeColors = darkColorScheme(
    primary = Acid,
    onPrimary = Color(0xFF11150A),
    secondary = Color(0xFF94D9FF),
    background = Ink,
    onBackground = Color(0xFFF2F3F5),
    surface = Panel,
    onSurface = Color(0xFFF2F3F5),
    surfaceVariant = SoftPanel,
    onSurfaceVariant = Color(0xFFD0D2D7),
    outline = Color(0xFF3B3E46),
)

@Composable
fun LumaGradeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LumaGradeColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
