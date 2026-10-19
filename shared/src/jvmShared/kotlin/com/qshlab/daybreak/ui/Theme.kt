package com.qshlab.daybreak.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The same family as Folio: neutral background, indigo accent; a warm sun yellow for Daybreak.
val Accent = Color(0xFF3F51D1)
val Sun = Color(0xFFFFC857)
val Danger = Color(0xFFC2412D)

@Composable
fun DaybreakTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF8B9BFF), onPrimary = Color(0xFF0F1430), primaryContainer = Color(0xFF232A4A), onPrimaryContainer = Color(0xFFDDE2FF),
        background = Color(0xFF121419), surface = Color(0xFF121419), surfaceContainer = Color(0xFF1A1D24), surfaceContainerHigh = Color(0xFF20242C),
        surfaceContainerLow = Color(0xFF16181E), onSurface = Color(0xFFE5E8EE), onSurfaceVariant = Color(0xFF949CAD), outlineVariant = Color(0xFF2A2E38),
        error = Color(0xFFF0907B),
    ) else lightColorScheme(
        primary = Accent, onPrimary = Color.White, primaryContainer = Color(0xFFE8EBFB), onPrimaryContainer = Accent,
        background = Color(0xFFF6F7F9), surface = Color(0xFFF6F7F9), surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEEF0F4),
        surfaceContainerLow = Color(0xFFFAFBFC), onSurface = Color(0xFF1B2130), onSurfaceVariant = Color(0xFF626B7D), outlineVariant = Color(0xFFE1E5EC),
        error = Danger,
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, modifier, color = color, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
