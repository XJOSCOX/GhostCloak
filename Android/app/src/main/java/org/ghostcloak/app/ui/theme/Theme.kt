package org.ghostcloak.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val GhostColors = darkColorScheme(
    primary = Mint, onPrimary = Ink, primaryContainer = PanelRaised, onPrimaryContainer = Mint,
    secondary = Muted, background = Ink, surface = Panel, onSurface = SoftWhite,
    onBackground = SoftWhite, onSurfaceVariant = Muted, surfaceVariant = PanelRaised,
    outline = Outline, error = Danger, errorContainer = androidx.compose.ui.graphics.Color(0xFF422624)
)

@Composable
fun GhostCloakTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GhostColors, typography = Typography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content)
}
