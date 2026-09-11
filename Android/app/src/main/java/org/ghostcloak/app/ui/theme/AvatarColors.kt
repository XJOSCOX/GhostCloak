package org.ghostcloak.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Decorative name colors only; these never indicate trust, presence or delivery. */
@Composable fun avatarColors(name: String): Pair<Color, Color> {
    val light = listOf(
        0xFFE5ECFF to 0xFF3455AF, 0xFFEDE5FA to 0xFF72509D,
        0xFFDAEEE8 to 0xFF286959, 0xFFF8E8D8 to 0xFF8B5A29,
    )
    val dark = listOf(
        0xFF2C3D65 to 0xFFB7CBFF, 0xFF453651 to 0xFFDDC0FF,
        0xFF25483F to 0xFFA5DCCD, 0xFF4F3D2B to 0xFFF1CE9F,
    )
    val palette = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) dark else light
    val (background, foreground) = palette[(name.lowercase().hashCode() and Int.MAX_VALUE) % palette.size]
    return Color(background) to Color(foreground)
}
